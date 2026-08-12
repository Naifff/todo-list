package com.familytodo.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Задача и все её переходы. Ни Spring, ни JPA, ни одной аннотации — правила прав должны
 * проверяться без поднятия контекста.
 *
 * <p>Порядок проверок внутри каждого перехода: сначала права, потом состояние, потом аргументы.
 * Иначе посторонний по подделанному {@code callback_data} узнаёт статус чужой задачи из текста
 * ошибки.
 *
 * <p>Исполнителей может быть несколько, и главное правило про них несимметрично намеренно:
 * <b>«сделано» закрывает дело всем, «отказ» снимает только с себя</b>. «Сделано» — факт о мире, а
 * отказ — ответ на просьбу, и отвечает на неё каждый адресат за себя. Дело уходит в {@link
 * TaskStatus#DECLINED}, только когда отказались все.
 */
public final class Task {

    /** Совпадает с ограничением схемы: длиннее в колонку всё равно не влезет. */
    public static final int MAX_TITLE_LENGTH = 200;

    /** Место это ориентир, а не адрес доставки: «школа», «Zoom», «перег. «Ока»». */
    public static final int MAX_LOCATION_LENGTH = 100;

    private final long id;
    private final long familyId;
    private final long creatorId;
    private final Instant createdAt;

    private String title;

    /** Порядок сохраняется: он же порядок имён в карточке и в блоке расписания. */
    private final List<Assignment> assignments;

    private TaskStatus status;
    private Instant dueAt;
    private Instant closedAt;

    /** Занятое время — необязательное. Срок и интервал это разные вещи, см. V2__task_schedule.sql. */
    private Instant startsAt;

    private Instant endsAt;

    private String location;

    /**
     * Откуда взялось дело, если его создало правило.
     *
     * <p>Не украшение карточки: без этого «Удалить» на вхождении неотличимо от удаления обычного
     * дела, а джоба материализации возвращает стёртую строку в течение часа — человек удаляет дело,
     * и оно приходит обратно.
     */
    private final Long seriesId;

    /** Локальная дата вхождения — та же, что в {@code task.occurrence_on}: хранится, не выводится. */
    private final LocalDate occurrenceOn;

    private Task(
            long id,
            long familyId,
            String title,
            long creatorId,
            List<Assignment> assignments,
            TaskStatus status,
            Instant dueAt,
            Instant createdAt,
            Instant closedAt,
            Instant startsAt,
            Instant endsAt,
            String location,
            Long seriesId,
            LocalDate occurrenceOn) {
        this.id = id;
        this.familyId = familyId;
        this.title = requireValidTitle(title);
        this.creatorId = creatorId;
        this.assignments = requireAtLeastOne(assignments);
        this.status = status;
        this.dueAt = dueAt;
        this.createdAt = createdAt;
        this.closedAt = closedAt;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.location = location;
        this.seriesId = seriesId;
        this.occurrenceOn = requireOccurrencePair(seriesId, occurrenceOn);
    }

    public static Task create(
            long id,
            long familyId,
            String title,
            long creatorId,
            List<Assignee> assignees,
            Instant dueAt,
            Instant createdAt) {
        return new Task(
                id,
                familyId,
                title,
                creatorId,
                assignees.stream().map(Assignment::of).toList(),
                TaskStatus.OPEN,
                dueAt,
                createdAt,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Один исполнитель — частный случай, а не отдельная механика. */
    public static Task create(
            long id,
            long familyId,
            String title,
            long creatorId,
            Assignee assignee,
            Instant dueAt,
            Instant createdAt) {
        return create(id, familyId, title, creatorId, List.of(assignee), dueAt, createdAt);
    }

    /**
     * Вхождение серии: обычное дело, которое помнит, каким правилом и на какой день создано.
     *
     * <p>Помнить обязано само дело, а не только строка в базе: карточка должна показать, что дело
     * повторяющееся, а удаление — записать пропуск, иначе джоба вернёт стёртое вхождение.
     */
    public static Task createOccurrence(
            long id,
            long familyId,
            String title,
            long creatorId,
            List<Assignee> assignees,
            Instant dueAt,
            long seriesId,
            LocalDate occurrenceOn,
            Instant createdAt) {
        return new Task(
                id,
                familyId,
                title,
                creatorId,
                assignees.stream().map(Assignment::of).toList(),
                TaskStatus.OPEN,
                dueAt,
                createdAt,
                null,
                null,
                null,
                null,
                seriesId,
                occurrenceOn);
    }

    /** Восстановление из хранилища: состояние приходит как есть, без переигрывания переходов. */
    public static Task restore(
            long id,
            long familyId,
            String title,
            long creatorId,
            List<Assignment> assignments,
            TaskStatus status,
            Instant dueAt,
            Instant createdAt,
            Instant closedAt,
            Instant startsAt,
            Instant endsAt,
            String location,
            Long seriesId,
            LocalDate occurrenceOn) {
        return new Task(
                id,
                familyId,
                title,
                creatorId,
                assignments,
                status,
                dueAt,
                createdAt,
                closedAt,
                startsAt,
                endsAt,
                location,
                seriesId,
                occurrenceOn);
    }

    // --- переходы ---

    /**
     * Закрыть: действующий исполнитель, автор или любой родитель семьи.
     *
     * <p>Закрывает дело <b>целиком</b>, даже если исполнителей несколько: «сделано» это факт о мире,
     * а не персональный ответ. К врачу сходили — второму родителю дело держать больше не нужно.
     */
    public void complete(Actor actor, Instant now) {
        requireMember(actor, this::isActiveAssignee, this::isCreator, this::isParent);
        requireOpen();

        status = TaskStatus.DONE;
        closedAt = now;
    }

    /**
     * Отказаться: только исполнитель, и только за себя.
     *
     * <p>Отказ — ответ на просьбу, отвечать может лишь адресат. Адресатов бывает несколько, поэтому
     * отказ снимает дело с одного человека, а закрывается оно, лишь когда отказались все. Иначе
     * папино «не могу» стирало бы дело у мамы, которая как раз собиралась ехать.
     */
    public void decline(Actor actor, String reason, Instant now) {
        Actor.MemberActor member = requireMember(actor, this::isNamedAssignee);
        requireOpen();
        if (hasDeclined(member.memberId())) {
            throw invalidTransition("already declined by this member");
        }
        if (isBlank(reason)) {
            throw new IllegalArgumentException("decline reason is required");
        }

        replace(member.memberId(), existing -> existing.declined(reason, now));

        if (assignments.stream().allMatch(Assignment::hasDeclined)) {
            status = TaskStatus.DECLINED;
            closedAt = now;
        }
    }

    /** Вернуть в работу: исполнитель или автор. Родителю со стороны — нет. */
    public void reopen(Actor actor) {
        requireMember(actor, this::isNamedAssignee, this::isCreator);
        if (!status.isClosed()) {
            throw invalidTransition("task is already open");
        }

        assignments.replaceAll(Assignment::withoutRefusal);
        status = TaskStatus.OPEN;
        closedAt = null;
    }

    /** Править: автор; родитель — только если среди исполнителей есть ребёнок. */
    public void edit(Actor actor, String newTitle, Instant newDueAt) {
        requireEditor(actor);
        requireOpen();

        title = requireValidTitle(newTitle);
        dueAt = newDueAt;
    }

    /**
     * Поручить дело ещё одному. Право то же, что на правку: круг исполнителей задаёт тот, кто
     * просил.
     *
     * @return {@code true}, если человека действительно добавили; повторное добавление — не ошибка,
     *     нажатие могло продублироваться по подтормаживающей связи
     */
    public boolean assign(Actor actor, Assignee assignee) {
        requireEditor(actor);
        requireOpen();
        requireAssignee(assignee);

        if (isNamedAssignee(assignee.memberId())) {
            return false;
        }
        assignments.add(Assignment.of(assignee));
        return true;
    }

    /** Снять с дела. Последнего снять нельзя: дело без исполнителя — не просьба, а запись в никуда. */
    public boolean unassign(Actor actor, long memberId) {
        requireEditor(actor);
        requireOpen();

        if (!isNamedAssignee(memberId)) {
            return false;
        }
        if (assignments.size() == 1) {
            throw invalidTransition("a task must keep at least one assignee");
        }
        assignments.removeIf(assignment -> assignment.memberId() == memberId);
        return true;
    }

    /**
     * Назначить время и место. Право то же, что на правку: расписание задаёт тот, кто просил.
     *
     * <p>Конец без начала бессмыслен, поэтому запрещён. Начало без конца — нет: «в 8 утра» без
     * длительности встречается чаще, чем точный интервал.
     */
    public void schedule(Actor actor, Instant startsAt, Instant endsAt, String location) {
        requireEditor(actor);
        requireOpen();

        if (startsAt == null && endsAt != null) {
            throw new IllegalArgumentException("end without start");
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("end must be after start");
        }

        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.location = requireValidLocation(location);
    }

    /** Удаление стирает строку, поэтому домен только проверяет право — состояние не важно. */
    public void assertDeletableBy(Actor actor) {
        requireEditor(actor);
    }

    /**
     * Системное закрытие: участника исключили из семьи, его открытые задачи закрываются от имени
     * системы. Обычному участнику этот переход недоступен — иначе он обходит правило «decline
     * только исполнителю».
     *
     * <p>Причина проставляется всем, кто ещё не ответил: колонки на задаче под неё больше нет, а
     * человек, открывший карточку, должен увидеть, почему дело закрылось само.
     */
    /**
     * Участника исключили из семьи: снять его с дела.
     *
     * <p>Дело закрывается, только если он был последним исполнителем. Закрывать его при оставшихся
     * значило бы наказывать их за чужой уход: запись к врачу нужна второму родителю ровно так же,
     * как была нужна до исключения первого.
     *
     * <p>Решение о том, закрылось дело или нет, принимает домен, а не сервис: правило «последний
     * ушёл — дело закрыто» относится к самой задаче, и повторённое в вызывающем коде оно однажды
     * разошлось бы с этим.
     *
     * @return {@code true}, если дело закрылось — только тогда есть о чём сообщать автору
     */
    public boolean releaseBySystem(Actor actor, long memberId, String reason, Instant now) {
        requireSystem(actor);
        requireOpen();

        if (!isNamedAssignee(memberId)) {
            return false;
        }
        if (assignments.size() == 1) {
            cancelBySystem(actor, reason, now);
            return true;
        }
        assignments.removeIf(assignment -> assignment.memberId() == memberId);
        return false;
    }

    public void cancelBySystem(Actor actor, String reason, Instant now) {
        requireSystem(actor);
        requireOpen();
        if (isBlank(reason)) {
            throw new IllegalArgumentException("cancellation reason is required");
        }

        assignments.replaceAll(
                assignment ->
                        assignment.hasDeclined() ? assignment : assignment.declined(reason, now));
        status = TaskStatus.DECLINED;
        closedAt = now;
    }

    // --- права: вопросы ---

    /**
     * Ответы для вёрстки кнопок. Набор кнопок обязан отражать права, но не он их обеспечивает:
     * каждый переход проверяет актора заново, потому что нажатие приходит подделываемой строкой.
     *
     * <p>Вьюха спрашивает домен, а не повторяет его правила у себя, — иначе два набора условий
     * разойдутся, и кнопка «Готово» появится там, где нажатие даст отказ.
     */
    public boolean mayComplete(Actor actor) {
        return !status.isClosed()
                && allows(actor, this::isActiveAssignee, this::isCreator, this::isParent);
    }

    /** Отказаться можно один раз: тот, кто уже ответил, кнопки не видит. */
    public boolean mayDecline(Actor actor) {
        return !status.isClosed() && allows(actor, this::isActiveAssignee);
    }

    public boolean mayReopen(Actor actor) {
        return status.isClosed() && allows(actor, this::isNamedAssignee, this::isCreator);
    }

    public boolean mayModify(Actor actor) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            return false;
        }
        return isCreator(member) || (isParent(member) && hasChildAssignee());
    }

    @SafeVarargs
    private boolean allows(Actor actor, java.util.function.Predicate<Actor.MemberActor>... any) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            return false;
        }
        for (java.util.function.Predicate<Actor.MemberActor> rule : any) {
            if (rule.test(member)) {
                return true;
            }
        }
        return false;
    }

    // --- права: проверки ---

    @SafeVarargs
    private Actor.MemberActor requireMember(
            Actor actor, java.util.function.Predicate<Actor.MemberActor>... any) {
        Actor.MemberActor member = asFamilyMember(actor);
        for (java.util.function.Predicate<Actor.MemberActor> rule : any) {
            if (rule.test(member)) {
                return member;
            }
        }
        throw new DomainException.NotPermitted("actor may not act on this task");
    }

    private void requireEditor(Actor actor) {
        Actor.MemberActor member = asFamilyMember(actor);
        if (isCreator(member) || (isParent(member) && hasChildAssignee())) {
            return;
        }
        throw new DomainException.NotPermitted("actor may not modify this task");
    }

    /**
     * Защита в глубину: изоляцию держит фильтр по {@code family_id} в SQL, но подделанный {@code
     * callback_data} с чужим id не должен пройти и здесь.
     */
    private Actor.MemberActor asFamilyMember(Actor actor) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            throw new DomainException.NotPermitted("actor does not belong to this family");
        }
        return member;
    }

    /**
     * Числится исполнителем — независимо от того, отказался он или нет.
     *
     * <p>Разведено с {@link #isActiveAssignee} ради порядка проверок: повторный отказ должен быть
     * «уже ответил» (состояние), а не «вам нельзя» (право). Иначе по тексту ошибки нельзя отличить
     * свою вторую попытку от чужого подделанного нажатия.
     */
    private boolean isNamedAssignee(Actor.MemberActor member) {
        return isNamedAssignee(member.memberId());
    }

    private boolean isNamedAssignee(long memberId) {
        return assignments.stream().anyMatch(assignment -> assignment.memberId() == memberId);
    }

    /** Исполнитель, который ещё не отказался: только у него дело действительно висит. */
    private boolean isActiveAssignee(Actor.MemberActor member) {
        return assignments.stream()
                .anyMatch(
                        assignment ->
                                assignment.memberId() == member.memberId()
                                        && !assignment.hasDeclined());
    }

    private boolean isCreator(Actor.MemberActor member) {
        return member.memberId() == creatorId;
    }

    private boolean isParent(Actor.MemberActor member) {
        return member.role() == Role.PARENT;
    }

    private boolean hasChildAssignee() {
        return assignments.stream().anyMatch(Assignment::isChild);
    }

    // --- состояние и валидация ---

    private void replace(long memberId, java.util.function.UnaryOperator<Assignment> change) {
        for (int i = 0; i < assignments.size(); i++) {
            if (assignments.get(i).memberId() == memberId) {
                assignments.set(i, change.apply(assignments.get(i)));
                return;
            }
        }
    }

    private static void requireSystem(Actor actor) {
        if (!(actor instanceof Actor.SystemActor)) {
            throw new DomainException.NotPermitted("only the system may cancel a task");
        }
    }

    private void requireOpen() {
        if (status.isClosed()) {
            throw invalidTransition("task is already closed");
        }
    }

    private DomainException.InvalidTransition invalidTransition(String message) {
        return new DomainException.InvalidTransition(status, message);
    }

    /**
     * Дубликаты снимаются здесь, а не в вызывающем коде: назначить одного человека дважды нельзя ни
     * при создании, ни при добавлении, и правило должно быть одно на оба пути.
     */
    private static List<Assignment> requireAtLeastOne(List<Assignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalArgumentException("at least one assignee is required");
        }
        Map<Long, Assignment> unique = new LinkedHashMap<>();
        for (Assignment assignment : assignments) {
            unique.putIfAbsent(assignment.memberId(), assignment);
        }
        return new ArrayList<>(unique.values());
    }

    private static void requireAssignee(Assignee assignee) {
        if (assignee == null) {
            throw new IllegalArgumentException("assignee is required");
        }
    }

    private static String requireValidTitle(String title) {
        if (isBlank(title)) {
            throw new IllegalArgumentException("title is required");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title is longer than " + MAX_TITLE_LENGTH);
        }
        return title;
    }

    private static String requireValidLocation(String location) {
        if (isBlank(location)) {
            return null;
        }
        if (location.length() > MAX_LOCATION_LENGTH) {
            throw new IllegalArgumentException("location is longer than " + MAX_LOCATION_LENGTH);
        }
        return location;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Серия и дата вхождения идут только парой: дата без серии не значит ничего, а серия без даты
     * оставила бы удаление без ответа на вопрос «какой день пропускаем».
     */
    private static LocalDate requireOccurrencePair(Long seriesId, LocalDate occurrenceOn) {
        if ((seriesId == null) != (occurrenceOn == null)) {
            throw new IllegalArgumentException("series and occurrence date go together");
        }
        return occurrenceOn;
    }

    // --- чтение ---

    /** Дело создано правилом, а не человеком: удаление и карточка ведут себя иначе. */
    public boolean isOccurrence() {
        return seriesId != null;
    }

    public Long seriesId() {
        return seriesId;
    }

    public LocalDate occurrenceOn() {
        return occurrenceOn;
    }

    public long id() {
        return id;
    }

    public long familyId() {
        return familyId;
    }

    public String title() {
        return title;
    }

    public long creatorId() {
        return creatorId;
    }

    public List<Assignment> assignments() {
        return Collections.unmodifiableList(assignments);
    }

    /** Идентификаторы тех, кто ещё не отказался, — кому дело действительно висит. */
    public List<Long> activeAssigneeIds() {
        return assignments.stream()
                .filter(assignment -> !assignment.hasDeclined())
                .map(Assignment::memberId)
                .toList();
    }

    public boolean isDeclinedBy(long memberId) {
        return hasDeclined(memberId);
    }

    public Optional<String> declineReasonOf(long memberId) {
        return assignments.stream()
                .filter(assignment -> assignment.memberId() == memberId)
                .map(Assignment::declineReason)
                .findFirst();
    }

    public TaskStatus status() {
        return status;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public String location() {
        return location;
    }

    public boolean isScheduled() {
        return startsAt != null;
    }

    /** Дело только на себя — когда исполнитель один и это автор. Вдвоём с кем-то уже не «себе». */
    public boolean isSelfAssigned() {
        return assignments.size() == 1 && assignments.get(0).memberId() == creatorId;
    }

    private boolean hasDeclined(long memberId) {
        return assignments.stream()
                .anyMatch(
                        assignment ->
                                assignment.memberId() == memberId && assignment.hasDeclined());
    }
}
