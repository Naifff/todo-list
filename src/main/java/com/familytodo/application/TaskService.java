package com.familytodo.application;

import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.Notifier;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Юзкейсы задач.
 *
 * <p>Права проверяет домен — сервис их не дублирует. Его работа: найти задачу в границах семьи
 * актора, применить переход, сохранить и разослать уведомления.
 *
 * <p>Общее правило уведомлений: сообщаем тому, кого это касается и кто не сам это сделал.
 * Самоназначенные задачи не порождают уведомлений вообще.
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
        requireActive(creator);
        Member assignee = requireActiveMember(creator.familyId(), assigneeId);

        Task task =
                Task.create(
                        tasks.nextId(),
                        creator.familyId(),
                        title,
                        creator.id(),
                        new Assignee(assignee.id(), assignee.role()),
                        dueAt,
                        clock.instant());
        Task saved = tasks.save(task);

        if (!saved.isSelfAssigned()) {
            notify(assignee, r -> notifier.taskAssigned(r, saved));
        }
        return saved;
    }

    public Task complete(Member actor, long taskId) {
        Task task = load(actor, taskId);
        task.complete(actor.asActor(), clock.instant());
        Task saved = tasks.save(task);

        notifyMember(actor, saved.creatorId(), r -> notifier.taskCompleted(r, saved, actor));
        return saved;
    }

    public Task decline(Member actor, long taskId, String reason) {
        Task task = load(actor, taskId);
        task.decline(actor.asActor(), reason, clock.instant());
        Task saved = tasks.save(task);

        notifyMember(
                actor, saved.creatorId(), r -> notifier.taskDeclined(r, saved, actor, reason));
        return saved;
    }

    /** Вернули в работу — знать об этом должен тот, кому она снова висит. */
    public Task reopen(Member actor, long taskId) {
        Task task = load(actor, taskId);
        task.reopen(actor.asActor());
        Task saved = tasks.save(task);

        long recipient =
                actor.id() == saved.assignee().memberId()
                        ? saved.creatorId()
                        : saved.assignee().memberId();
        notifyMember(actor, recipient, r -> notifier.taskReopened(r, saved, actor));
        return saved;
    }

    public Task edit(Member actor, long taskId, String title, Instant dueAt) {
        Task task = load(actor, taskId);
        task.edit(actor.asActor(), title, dueAt);
        return tasks.save(task);
    }

    /**
     * Смена исполнителя. Уведомляются обе стороны: новый — что на нём дело, прежний — что с него
     * сняли. Молча переложить просьбу на другого нельзя, иначе первый продолжит её держать в голове.
     */
    public Task reassign(Member actor, long taskId, long newAssigneeId) {
        Task task = load(actor, taskId);
        Member newAssignee = requireActiveMember(actor.familyId(), newAssigneeId);

        Assignee previous =
                task.reassign(
                        actor.asActor(), new Assignee(newAssignee.id(), newAssignee.role()));
        Task saved = tasks.save(task);

        if (previous.memberId() != newAssignee.id()) {
            notifyMember(actor, previous.memberId(), r -> notifier.taskUnassigned(r, saved));
            notifyMember(actor, newAssignee.id(), r -> notifier.taskAssigned(r, saved));
        }
        return saved;
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
                        || task.assignee().memberId() == actor.id()
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

    /** Себе не пишем, недостижимым тоже: заблокировавшим бота и исключённым отправлять некуда. */
    private void notifyMember(Member actor, long recipientId, java.util.function.Consumer<Member> send) {
        if (recipientId == actor.id()) {
            return;
        }
        members.findById(actor.familyId(), recipientId).ifPresent(r -> notify(r, send));
    }

    private void notify(Member recipient, java.util.function.Consumer<Member> send) {
        if (recipient.isReachable()) {
            send.accept(recipient);
        }
    }
}
