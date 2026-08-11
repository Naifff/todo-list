package com.familytodo.application;

import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.Notifier;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Assignment;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Юзкейсы задач.
 *
 * <p>Права проверяет домен — сервис их не дублирует. Его работа: найти задачу в границах семьи
 * актора, применить переход, сохранить и разослать уведомления.
 *
 * <p>Общее правило уведомлений: сообщаем тому, кого это касается и кто не сам это сделал.
 * Самоназначенные задачи не порождают уведомлений вообще.
 *
 * <p>Когда исполнителей несколько, «кого это касается» расширяется на них всех: если мама закрыла
 * запись к врачу, папа должен перестать держать её в голове — ради этого фича и делалась.
 */
public class TaskService {

    private final TaskRepository tasks;
    private final MemberRepository members;
    private final Notifier notifier;
    private final Clock clock;

    public TaskService(
            TaskRepository tasks, MemberRepository members, Notifier notifier, Clock clock) {
        this.tasks = tasks;
        this.members = members;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Создать задачу может любой активный участник — в том числе ребёнок, и в том числе родителю. */
    public Task create(Member creator, long assigneeId, String title, Instant dueAt) {
        return create(creator, List.of(assigneeId), title, dueAt);
    }

    /**
     * Поручить дело сразу нескольким.
     *
     * <p>Порядок сохраняется: он же порядок имён в карточке. Повторы снимает домен — назначить
     * одного человека дважды нельзя ни здесь, ни потом кнопкой.
     */
    public Task create(Member creator, List<Long> assigneeIds, String title, Instant dueAt) {
        requireActive(creator);

        List<Member> assignees = new ArrayList<>();
        for (long assigneeId : new LinkedHashSet<>(assigneeIds)) {
            assignees.add(requireActiveMember(creator.familyId(), assigneeId));
        }

        Task task =
                Task.create(
                        tasks.nextId(),
                        creator.familyId(),
                        title,
                        creator.id(),
                        assignees.stream()
                                .map(member -> new Assignee(member.id(), member.role()))
                                .toList(),
                        dueAt,
                        clock.instant());
        Task saved = tasks.save(task);

        for (Member assignee : assignees) {
            if (assignee.id() != creator.id()) {
                notify(assignee, r -> notifier.taskAssigned(r, saved));
            }
        }
        return saved;
    }

    public Task complete(Member actor, long taskId) {
        Task task = load(actor, taskId);
        List<Long> involved = involved(task);
        task.complete(actor.asActor(), clock.instant());
        Task saved = tasks.save(task);

        notifyEach(actor, involved, r -> notifier.taskCompleted(r, saved, actor));
        return saved;
    }

    /**
     * Отказ снимает дело с одного человека, а не со всех.
     *
     * <p>Знать об этом должны и автор, и остальные исполнители: «папа не может» для мамы значит
     * «еду я», и без сообщения она узнает об этом, только открыв карточку.
     */
    public Task decline(Member actor, long taskId, String reason) {
        Task task = load(actor, taskId);
        List<Long> involved = involved(task);
        task.decline(actor.asActor(), reason, clock.instant());
        Task saved = tasks.save(task);

        notifyEach(actor, involved, r -> notifier.taskDeclined(r, saved, actor, reason));
        return saved;
    }

    /** Вернули в работу — знать об этом должен тот, кому она снова висит. */
    public Task reopen(Member actor, long taskId) {
        Task task = load(actor, taskId);
        task.reopen(actor.asActor());
        Task saved = tasks.save(task);

        notifyEach(actor, involved(saved), r -> notifier.taskReopened(r, saved, actor));
        return saved;
    }

    public Task edit(Member actor, long taskId, String title, Instant dueAt) {
        Task task = load(actor, taskId);
        task.edit(actor.asActor(), title, dueAt);
        return tasks.save(task);
    }

    /**
     * Смена исполнителя. Уведомляются обе стороны: новый — что на нём дело, прежние — что с них
     * сняли. Молча переложить просьбу на другого нельзя, иначе первый продолжит держать её в голове.
     */
    public Task reassign(Member actor, long taskId, long newAssigneeId) {
        Task task = load(actor, taskId);
        Member newAssignee = requireActiveMember(actor.familyId(), newAssigneeId);

        List<Assignment> previous =
                task.reassign(
                        actor.asActor(), new Assignee(newAssignee.id(), newAssignee.role()));
        Task saved = tasks.save(task);

        for (Assignment gone : previous) {
            if (gone.memberId() != newAssignee.id()) {
                notifyMember(actor, gone.memberId(), r -> notifier.taskUnassigned(r, saved));
            }
        }
        if (previous.stream().noneMatch(gone -> gone.memberId() == newAssignee.id())) {
            notifyMember(actor, newAssignee.id(), r -> notifier.taskAssigned(r, saved));
        }
        return saved;
    }

    /** Поручить дело ещё одному — круг исполнителей расширяется, прежние остаются. */
    public Task assign(Member actor, long taskId, long assigneeId) {
        Task task = load(actor, taskId);
        Member assignee = requireActiveMember(actor.familyId(), assigneeId);

        boolean added =
                task.assign(actor.asActor(), new Assignee(assignee.id(), assignee.role()));
        Task saved = tasks.save(task);

        if (added) {
            notifyMember(actor, assignee.id(), r -> notifier.taskAssigned(r, saved));
        }
        return saved;
    }

    /** Снять с дела. Последнего снять нельзя — это проверяет домен. */
    public Task unassign(Member actor, long taskId, long assigneeId) {
        Task task = load(actor, taskId);
        boolean removed = task.unassign(actor.asActor(), assigneeId);
        Task saved = tasks.save(task);

        if (removed) {
            notifyMember(actor, assigneeId, r -> notifier.taskUnassigned(r, saved));
        }
        return saved;
    }

    /** Время и место. Уведомлений не шлём: это уточнение уже принятой просьбы, а не новая. */
    public Task schedule(
            Member actor, long taskId, Instant startsAt, Instant endsAt, String location) {
        Task task = load(actor, taskId);
        task.schedule(actor.asActor(), startsAt, endsAt, location);
        return tasks.save(task);
    }

    public void delete(Member actor, long taskId) {
        Task task = load(actor, taskId);
        task.assertDeletableBy(actor.asActor());
        tasks.delete(actor.familyId(), task.id());
    }

    public List<Task> find(TaskQuery query) {
        return tasks.find(query);
    }

    /**
     * Одна задача с учётом видимости — для карточки.
     *
     * <p>Отличается от загрузки для перехода: там достаточно границы семьи, потому что право
     * проверит домен, а существование задачи внутри своей семьи не секрет. Здесь же наружу идёт
     * содержимое — название, кто просил, причина отказа, — и ребёнок не должен прочитать чужое даже
     * по прямому id. Поэтому ответ именно «не найдено».
     */
    public Task findVisible(Member actor, long taskId) {
        Task task = load(actor, taskId);
        boolean visible =
                actor.isParent()
                        || task.assignments().stream()
                                .anyMatch(assignment -> assignment.memberId() == actor.id())
                        || task.creatorId() == actor.id();
        if (!visible) {
            throw new DomainException.NotFound("task " + taskId + " not found");
        }
        return task;
    }

    /**
     * Задача ищется только внутри семьи актора. Чужая не «запрещена», а не существует — по
     * подделанному {@code callback_data} нельзя узнать даже факт её наличия.
     */
    private Task load(Member actor, long taskId) {
        return tasks.findById(actor.familyId(), taskId)
                .orElseThrow(() -> new DomainException.NotFound("task " + taskId + " not found"));
    }

    private Member requireActiveMember(long familyId, long memberId) {
        return members.findById(familyId, memberId)
                .filter(Member::isActive)
                .orElseThrow(
                        () -> new DomainException.NotFound("member " + memberId + " not found"));
    }

    private void requireActive(Member member) {
        if (!member.isActive()) {
            throw new DomainException.NotPermitted("removed member may not act");
        }
    }

    /**
     * Все, кого касается судьба дела: автор и исполнители.
     *
     * <p>Снимается <b>до</b> перехода, потому что переход список меняет: снятый с дела человек всё
     * ещё должен узнать, что дело закрыли.
     */
    private static List<Long> involved(Task task) {
        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(task.creatorId());
        task.assignments().forEach(assignment -> recipients.add(assignment.memberId()));
        return List.copyOf(recipients);
    }

    private void notifyEach(Member actor, List<Long> recipientIds, Consumer<Member> send) {
        recipientIds.forEach(recipientId -> notifyMember(actor, recipientId, send));
    }

    /** Себе не пишем, недостижимым тоже: заблокировавшим бота и исключённым отправлять некуда. */
    private void notifyMember(Member actor, long recipientId, Consumer<Member> send) {
        if (recipientId == actor.id()) {
            return;
        }
        members.findById(actor.familyId(), recipientId).ifPresent(r -> notify(r, send));
    }

    private void notify(Member recipient, Consumer<Member> send) {
        if (recipient.isReachable()) {
            send.accept(recipient);
        }
    }
}
