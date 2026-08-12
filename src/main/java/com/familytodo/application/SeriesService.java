package com.familytodo.application;

import com.familytodo.application.port.FamilyRepository;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.application.port.TaskSeriesRepository;
import com.familytodo.domain.Actor;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskSeries;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Повторяющиеся дела: правило и его превращение в обычные задачи.
 *
 * <p>Вхождения материализуются строками {@code task}, а не вычисляются на лету: у каждого своя
 * судьба — одно сделано, другое отклонено с причиной, третьему сменили исполнителя. Виртуальному
 * вхождению статус приписать некуда.
 *
 * <p>Идемпотентность держит не этот код, а уникальный индекс по паре {@code (series_id,
 * occurrence_on)}: между запросом «каких дат не хватает» и вставкой мог пройти другой прогон.
 */
@Service
public class SeriesService {

    /** Горизонт {@code /agenda} плюс запас: человек не должен упереться в край календаря. */
    public static final int HORIZON_DAYS = 60;

    private static final Logger log = LoggerFactory.getLogger(SeriesService.class);

    private final FamilyRepository families;
    private final TaskSeriesRepository series;
    private final TaskRepository tasks;
    private final MemberRepository members;
    private final Clock clock;

    public SeriesService(
            FamilyRepository families,
            TaskSeriesRepository series,
            TaskRepository tasks,
            MemberRepository members,
            Clock clock) {
        this.families = families;
        this.series = series;
        this.tasks = tasks;
        this.members = members;
        this.clock = clock;
    }

    /**
     * Завести серию и сразу заполнить горизонт: ждать до ближайшего часа джобы значило бы, что
     * человек нажал «по будням» и ничего не увидел.
     */
    public TaskSeries create(
            Member creator,
            long assigneeId,
            String title,
            Recurrence recurrence,
            LocalTime startTime,
            Duration duration,
            String location,
            LocalDate startsOn) {
        return create(
                creator,
                List.of(assigneeId),
                title,
                recurrence,
                startTime,
                duration,
                location,
                startsOn);
    }

    /** Повторяющееся дело сразу нескольким: тренировка, на которую возят по очереди. */
    public TaskSeries create(
            Member creator,
            List<Long> assigneeIds,
            String title,
            Recurrence recurrence,
            LocalTime startTime,
            Duration duration,
            String location,
            LocalDate startsOn) {

        requireActive(creator);
        List<Assignee> assignees = new ArrayList<>();
        for (long assigneeId : new LinkedHashSet<>(assigneeIds)) {
            Member member = requireActiveMember(creator.familyId(), assigneeId);
            assignees.add(new Assignee(member.id(), member.role()));
        }

        TaskSeries rule =
                TaskSeries.create(
                        series.nextId(),
                        creator.familyId(),
                        title,
                        creator.id(),
                        assignees,
                        recurrence,
                        startTime,
                        duration,
                        location,
                        startsOn,
                        null,
                        clock.instant());

        TaskSeries saved = series.save(rule);
        materialise(saved, family(creator.familyId()), clock.instant());
        log.info("series {} created in family {}", saved.id(), saved.familyId());
        return saved;
    }

    /** Активные правила, которые этому участнику положено видеть. */
    public List<TaskSeries> active(Member viewer) {
        return series.findActive(viewer.familyId(), visibilityLimit(viewer));
    }

    /** Одно правило — с той же проверкой видимости: чужое обязано выглядеть как несуществующее. */
    public TaskSeries require(Member viewer, long seriesId) {
        return series.findById(viewer.familyId(), visibilityLimit(viewer), seriesId)
                .orElseThrow(() -> new DomainException.NotFound("series not found"));
    }

    /** Ребёнок видит только свои правила, родитель — все семейные. Как и у задач. */
    private static Long visibilityLimit(Member viewer) {
        return viewer.role() == Role.CHILD ? viewer.id() : null;
    }

    /**
     * Остановить серию и убрать её будущие открытые вхождения.
     *
     * <p>Закрытые остаются: {@code DONE} и {@code DECLINED} — история семьи, и остановка правила не
     * повод её стирать. Строка серии тоже остаётся: на неё ссылается эта история.
     */
    public Stopped stop(Member actor, long seriesId) {
        TaskSeries rule = require(actor, seriesId);

        rule.stop(actor.asActor(), clock.instant());
        series.save(rule);

        int removed =
                tasks.deleteOpenOccurrencesFrom(
                        rule.familyId(), rule.id(), family(actor.familyId()).today(clock.instant()));
        log.info("series {} stopped, {} future occurrences removed", rule.id(), removed);
        return new Stopped(rule, removed);
    }

    /**
     * Сколько дел ушло — часть ответа, а не подробность для журнала: остановка вечерней тренировки
     * убирает из списков и календаря семьи десяток дел, и человек вправе увидеть это числом.
     */
    public record Stopped(TaskSeries series, int removed) {}

    /** Заполнить горизонт по всем семьям. Вызывается джобой, «смотрящего» у неё нет. */
    public void materialiseAll() {
        Instant now = clock.instant();
        Map<Long, Family> byId = new HashMap<>();
        for (Family family : families.findAll()) {
            byId.put(family.id(), family);
        }

        for (TaskSeries rule : series.findActive()) {
            Family family = byId.get(rule.familyId());
            if (family != null) {
                materialise(rule, family, now);
            }
        }
    }

    private void materialise(TaskSeries rule, Family family, Instant now) {
        ZoneId zone = family.timezone();
        LocalDate from = family.today(now);
        LocalDate to = from.plusDays(HORIZON_DAYS - 1L);

        Set<LocalDate> already = tasks.occurrenceDates(rule.familyId(), rule.id(), from, to);

        int created = 0;
        for (LocalDate date : rule.occurrencesBetween(from, to)) {
            if (already.contains(date)) {
                continue;
            }
            tasks.saveOccurrence(occurrence(rule, date, zone, now), rule.id(), date);
            created++;
        }
        if (created > 0) {
            log.info("series {} materialised {} occurrences", rule.id(), created);
        }
    }

    /**
     * Момент считается по зоне семьи <b>на момент создания</b> и дальше не пересчитывается. Смена
     * зоны не должна задним числом переносить встречи, о которых люди уже договорились.
     */
    private Task occurrence(TaskSeries rule, LocalDate date, ZoneId zone, Instant now) {
        Instant startsAt = rule.startOf(date, zone);
        Instant endsAt = rule.endOf(date, zone);

        if (endsAt == null) {
            // без длительности вхождение — обычное дело со сроком
            return Task.create(
                    tasks.nextId(),
                    rule.familyId(),
                    rule.title(),
                    rule.creatorId(),
                    rule.assignees(),
                    startsAt,
                    now);
        }

        Task task =
                Task.create(
                        tasks.nextId(),
                        rule.familyId(),
                        rule.title(),
                        rule.creatorId(),
                        rule.assignees(),
                        null,
                        now);
        // расписание ставит автор серии: он же его и задал, когда заводил правило
        task.schedule(
                Actor.member(rule.creatorId(), rule.familyId(), Role.PARENT),
                startsAt,
                endsAt,
                rule.location());
        return task;
    }

    private Family family(long familyId) {
        return families.findById(familyId)
                .orElseThrow(() -> new DomainException.NotFound("family not found"));
    }

    private static void requireActive(Member member) {
        if (member.status() != MemberStatus.ACTIVE) {
            throw new DomainException.NotPermitted("member is removed");
        }
    }

    private Member requireActiveMember(long familyId, long memberId) {
        Member member =
                members.findById(familyId, memberId)
                        .orElseThrow(() -> new DomainException.NotFound("member not found"));
        requireActive(member);
        return member;
    }
}
