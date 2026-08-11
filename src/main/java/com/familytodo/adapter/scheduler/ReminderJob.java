package com.familytodo.adapter.scheduler;

import com.familytodo.application.DueDateParser;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.Notifier;
import com.familytodo.application.port.ReminderRepository;
import com.familytodo.application.port.ReminderRepository.DueReminder;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Напоминания о наступившем сроке.
 *
 * <p>Порядок обязателен: <b>пометить → закоммитить → отправить</b>. Отправка внутри той же
 * транзакции ломает гарантию — откат после успешной доставки даёт ровно тот дубль, который мы
 * предотвращаем. Обратная сторона выбора честная: падение между отметкой и отправкой теряет
 * напоминание. Потерять одно лучше, чем разбудить семью двумя одинаковыми.
 */
@Component
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    /**
     * Насколько опоздавшее напоминание ещё имеет смысл отправлять.
     *
     * <p>Порог считается от <b>момента напоминания</b>, а не от срока: дело на 23:30 переносится
     * тихими часами на 08:00 и в восемь утра опоздавшим не считается.
     */
    private static final Duration MAX_LATE = Duration.ofHours(2);

    private static final int BATCH = 50;

    private final ReminderRepository reminders;
    private final TaskRepository tasks;
    private final MemberRepository members;
    private final Notifier notifier;
    private final DueDateParser dueDates;
    private final Clock clock;

    public ReminderJob(
            ReminderRepository reminders,
            TaskRepository tasks,
            MemberRepository members,
            Notifier notifier,
            DueDateParser dueDates,
            Clock clock) {
        this.reminders = reminders;
        this.tasks = tasks;
        this.members = members;
        this.notifier = notifier;
        this.dueDates = dueDates;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${reminders.interval-ms:60000}")
    public void run() {
        Instant now = clock.instant();
        List<DueReminder> due = reminders.findDue(now, BATCH);
        if (due.isEmpty()) {
            return;
        }

        List<Long> toMark = new ArrayList<>();
        List<DueReminder> toSend = new ArrayList<>();

        for (DueReminder reminder : due) {
            Instant when = dueDates.reminderTimeFor(reminder.dueAt(), reminder.zone());
            if (when.isAfter(now)) {
                // тихие часы: срок наступил, но будить рано — вернёмся к этому утром
                continue;
            }
            toMark.add(reminder.taskId());

            if (Duration.between(when, now).compareTo(MAX_LATE) <= 0) {
                toSend.add(reminder);
            } else {
                // приложение простояло: помечаем, но не шлём пачку сообщений задним числом
                log.info("reminder for task {} skipped as too late", reminder.taskId());
            }
        }

        if (toMark.isEmpty()) {
            return;
        }

        // отметка коммитится здесь; всё, что ниже, происходит уже после неё
        reminders.markReminded(toMark, now);

        for (DueReminder reminder : toSend) {
            send(reminder);
        }
    }

    /**
     * Напоминание уходит каждому, кому дело ещё висит.
     *
     * <p>Отказавшиеся исключены: человек уже ответил «не могу», и напоминать ему о том, от чего он
     * отказался, значит превращать напоминание в укор.
     */
    private void send(DueReminder reminder) {
        tasks.findById(reminder.familyId(), reminder.taskId())
                .ifPresent(
                        task ->
                                task.activeAssigneeIds()
                                        .forEach(
                                                assigneeId ->
                                                        members.findById(
                                                                        reminder.familyId(),
                                                                        assigneeId)
                                                                .filter(Member::isReachable)
                                                                .ifPresent(
                                                                        recipient ->
                                                                                notify(
                                                                                        recipient,
                                                                                        task))));
    }

    /**
     * Сбой доставки одному не отменяет остальных в пачке.
     *
     * <p>Реализация {@link Notifier} и сама не бросает, но полагаться на это здесь нельзя: порядок
     * «пометить → отправить» уже выполнен, и исключение отсюда не вернуло бы задачу в очередь — оно
     * лишь оборвало бы рассылку остальным.
     */
    private void notify(Member recipient, Task task) {
        try {
            notifier.taskDue(recipient, task);
            log.info("reminder sent for task {}", task.id());
        } catch (RuntimeException e) {
            log.warn("reminder for task {} not delivered", task.id(), e);
        }
    }
}
