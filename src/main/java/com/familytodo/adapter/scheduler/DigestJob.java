package com.familytodo.adapter.scheduler;

import com.familytodo.application.TaskQuery;
import com.familytodo.application.port.FamilyRepository;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.Notifier;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Утренний список дел.
 *
 * <p>Одна джоба на все семьи: она просыпается раз в 15 минут и сверяет **локальное** время каждой.
 * Поэтому семьи в разных часовых поясах получают дайджест каждая в своё утро, без отдельного
 * расписания на семью.
 *
 * <p>Порядок тот же, что у напоминаний: отметка о рассылке коммитится <b>до</b> отправки. Иначе
 * сбой посередине приводит к повторному дайджесту.
 */
@Component
public class DigestJob {

    private static final Logger log = LoggerFactory.getLogger(DigestJob.class);

    /**
     * Крайний срок догоняющей рассылки.
     *
     * <p>Приложение могло лежать всё утро. Пропущенный дайджест имеет смысл дослать в десять, но не
     * в семь вечера: «дела на сегодня» к тому времени уже не новость.
     */
    private static final LocalTime TOO_LATE = LocalTime.NOON;

    /** Дневной дайджест: «что сегодня». Его горизонт не настраивается — это и есть сегодня. */
    private static final int DAY = 1;

    private final FamilyRepository families;
    private final MemberRepository members;
    private final TaskRepository tasks;
    private final Notifier notifier;
    private final Clock clock;

    public DigestJob(
            FamilyRepository families,
            MemberRepository members,
            TaskRepository tasks,
            Notifier notifier,
            Clock clock) {
        this.families = families;
        this.members = members;
        this.tasks = tasks;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${digest.interval-ms:900000}")
    public void run() {
        Instant now = clock.instant();
        for (Family family : families.findAll()) {
            if (isTime(family, now)) {
                sendDigest(family, now);
            }
        }
    }

    private boolean isTime(Family family, Instant now) {
        ZonedDateTime local = now.atZone(family.timezone());
        LocalDate today = local.toLocalDate();

        if (!family.lastDigestDate().isBefore(today)) {
            return false; // сегодня уже отправляли — или семья создана сегодня
        }
        LocalTime time = local.toLocalTime();
        return !time.isBefore(family.digestTime()) && time.isBefore(TOO_LATE);
    }

    private void sendDigest(Family family, Instant now) {
        // помечаем и коммитим до рассылки: сбой посередине не должен давать второй дайджест
        family.markDigestSent(family.today(now));
        families.save(family);

        List<Member> roster = members.findActive(family.id());
        int week = family.digestHorizonDays();
        for (Member recipient : roster) {
            if (!recipient.isReachable()) {
                continue;
            }
            // дневной отвечает «что сегодня», недельный — «к чему готовиться». Один список на
            // неделю отвечал на первый вопрос плохо: сегодняшнее тонуло среди дел на пятницу
            deliverIfAny(recipient, roster, family, now, DAY, true);
            if (week > DAY) {
                // горизонт в один день означает один дайджест: второй был бы его копией
                deliverIfAny(recipient, roster, family, now, week, false);
            }
        }
        log.info("digest sent for family {}", family.id());
    }

    /**
     * Что попадает в утренний список.
     *
     * <p>Горизонт ограничивает только <b>вперёд</b>. Назад его нет: просроченное с ростом срока не
     * становится менее важным, а дела без даты не с чем сравнивать — «купить хлеб» не обещано ни на
     * какой день, но из семейного списка от этого не исчезает.
     */
    private List<Task> withinHorizon(
            Member recipient, Family family, Instant now, int days, boolean includeUndated) {
        Instant until =
                family.today(now).plusDays(days).atStartOfDay(family.timezone()).toInstant();

        List<Task> visible = new ArrayList<>(tasks.find(TaskQuery.inRange(recipient, null, until)));
        if (includeUndated) {
            // ⚠️ только в дневной: иначе «купить хлеб» приходит дважды за одно утро, и человек
            // перестаёт читать оба списка
            visible.addAll(tasks.find(TaskQuery.undated(recipient)));
        }
        return visible;
    }

    private void deliverIfAny(
            Member recipient,
            List<Member> roster,
            Family family,
            Instant now,
            int days,
            boolean includeUndated) {
        List<Task> visible = withinHorizon(recipient, family, now, days, includeUndated);
        if (visible.isEmpty()) {
            // пустой дайджест не отправляем: сообщение «дел нет» это шум, а не польза
            return;
        }
        deliver(recipient, visible, roster, family, days);
    }

    private void deliver(
            Member recipient, List<Task> visible, List<Member> roster, Family family, int days) {
        try {
            notifier.digest(recipient, visible, roster, family.timezone(), days);
        } catch (RuntimeException e) {
            // один недоставленный дайджест не отменяет остальных в семье
            log.warn("digest for member {} not delivered", recipient.id(), e);
        }
    }
}
