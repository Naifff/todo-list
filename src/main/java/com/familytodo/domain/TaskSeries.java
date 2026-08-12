package com.familytodo.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Серия повторяющихся дел: правило, а не сами дела.
 *
 * <p>Вхождения материализуются обычными строками {@code task}, а не вычисляются на лету. У каждого
 * своя судьба: одно сделано, другое отклонено с причиной, третьему сменили исполнителя. Виртуальному
 * вхождению статус приписать некуда.
 *
 * <p><b>Ограничение v1, принятое сознательно:</b> правка применяется к одному вхождению, а серию
 * можно только остановить. «Изменить это или всю серию» — самый дорогой узел в календарях, и для
 * семейного списка он не окупается. Остановка не трогает уже случившиеся вхождения: история живёт в
 * задачах, а не в правиле.
 */
public final class TaskSeries {

    private final long id;
    private final long familyId;
    private final String title;
    private final long creatorId;
    private final List<Assignee> assignees;
    private final Recurrence recurrence;
    private final LocalTime startTime;
    private final Duration duration;
    private final String location;
    private final LocalDate startsOn;
    private final LocalDate endsOn;
    private final Instant createdAt;

    private Instant stoppedAt;

    private TaskSeries(
            long id,
            long familyId,
            String title,
            long creatorId,
            List<Assignee> assignees,
            Recurrence recurrence,
            LocalTime startTime,
            Duration duration,
            String location,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant stoppedAt,
            Instant createdAt) {
        this.id = id;
        this.familyId = familyId;
        this.title = requireValidTitle(title);
        this.creatorId = creatorId;
        this.assignees = requireAssignees(assignees);
        this.recurrence = requireRecurrence(recurrence);
        this.startTime = requireStartTime(startTime);
        this.duration = requireDuration(duration);
        this.location = location;
        this.startsOn = requireStart(startsOn);
        this.endsOn = requireEnd(startsOn, endsOn);
        this.stoppedAt = stoppedAt;
        this.createdAt = createdAt;
    }

    public static TaskSeries create(
            long id,
            long familyId,
            String title,
            long creatorId,
            List<Assignee> assignees,
            Recurrence recurrence,
            LocalTime startTime,
            Duration duration,
            String location,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant createdAt) {
        return new TaskSeries(
                id,
                familyId,
                title,
                creatorId,
                assignees,
                recurrence,
                startTime,
                duration,
                location,
                startsOn,
                endsOn,
                null,
                createdAt);
    }

    public static TaskSeries restore(
            long id,
            long familyId,
            String title,
            long creatorId,
            List<Assignee> assignees,
            Recurrence recurrence,
            LocalTime startTime,
            Duration duration,
            String location,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant stoppedAt,
            Instant createdAt) {
        return new TaskSeries(
                id,
                familyId,
                title,
                creatorId,
                assignees,
                recurrence,
                startTime,
                duration,
                location,
                startsOn,
                endsOn,
                stoppedAt,
                createdAt);
    }

    /**
     * Даты вхождений в окне, включая обе границы.
     *
     * <p>Перевёрнутое окно даёт пустой список, а не исключение: джоба считает границы из часов, и
     * подвинувшееся время не повод ронять рассылку всем семьям.
     */
    public List<LocalDate> occurrencesBetween(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        if (isStopped() || to.isBefore(from)) {
            return dates;
        }
        LocalDate first = from.isBefore(startsOn) ? startsOn : from;
        LocalDate last = endsOn != null && endsOn.isBefore(to) ? endsOn : to;

        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (recurrence.occursOn(date)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /** Момент вхождения в зоне семьи: 08:00 — это её восемь утра, а не серверные. */
    public Instant startOf(LocalDate date, ZoneId zone) {
        return date.atTime(startTime).atZone(zone).toInstant();
    }

    public Instant endOf(LocalDate date, ZoneId zone) {
        return duration == null ? null : startOf(date, zone).plus(duration);
    }

    /**
     * Остановить серию. Автор — или родитель, если исполнитель ребёнок: те же правила, что у правки
     * задачи, чтобы «кто чем распоряжается» не зависело от того, повторяется дело или нет.
     */
    public void stop(Actor actor, Instant now) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            throw new DomainException.NotPermitted("actor does not belong to this family");
        }
        boolean allowed =
                member.memberId() == creatorId
                        || (member.role() == Role.PARENT && hasChildAssignee());
        if (!allowed) {
            throw new DomainException.NotPermitted("actor may not stop this series");
        }
        if (stoppedAt == null) {
            // повторная остановка не переписывает момент: он попадает в историю
            stoppedAt = now;
        }
    }

    public boolean isStopped() {
        return stoppedAt != null;
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

    /** Порядок сохраняется: он же порядок имён в карточке каждого вхождения. */
    public List<Assignee> assignees() {
        return List.copyOf(assignees);
    }

    private boolean hasChildAssignee() {
        return assignees.stream().anyMatch(Assignee::isChild);
    }

    public Recurrence recurrence() {
        return recurrence;
    }

    public LocalTime startTime() {
        return startTime;
    }

    public Duration duration() {
        return duration;
    }

    public String location() {
        return location;
    }

    public LocalDate startsOn() {
        return startsOn;
    }

    public LocalDate endsOn() {
        return endsOn;
    }

    public Instant stoppedAt() {
        return stoppedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireValidTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("series title is required");
        }
        if (title.length() > Task.MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("series title is longer than " + Task.MAX_TITLE_LENGTH);
        }
        return title.strip();
    }

    /**
     * Повторяющееся дело без исполнителя — правило, которое некому выполнять. Дубликаты снимаются
     * здесь же, как и у задачи: правило должно быть одно на все пути.
     */
    private static List<Assignee> requireAssignees(List<Assignee> assignees) {
        if (assignees == null || assignees.isEmpty()) {
            throw new IllegalArgumentException("series needs at least one assignee");
        }
        Map<Long, Assignee> unique = new LinkedHashMap<>();
        assignees.forEach(assignee -> unique.putIfAbsent(assignee.memberId(), assignee));
        return List.copyOf(unique.values());
    }

    private static Recurrence requireRecurrence(Recurrence recurrence) {
        if (recurrence == null) {
            throw new IllegalArgumentException("series recurrence is required");
        }
        return recurrence;
    }

    private static LocalTime requireStartTime(LocalTime startTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("series start time is required");
        }
        return startTime;
    }

    private static Duration requireDuration(Duration duration) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException("series duration must be positive");
        }
        return duration;
    }

    private static LocalDate requireStart(LocalDate startsOn) {
        if (startsOn == null) {
            throw new IllegalArgumentException("series start date is required");
        }
        return startsOn;
    }

    private static LocalDate requireEnd(LocalDate startsOn, LocalDate endsOn) {
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("series ends before it starts");
        }
        return endsOn;
    }
}
