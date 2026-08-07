package com.familytodo.adapter.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Планировщик для цикла long polling, переживающий неожиданный сбой.
 *
 * <p>Задача, отданная в {@code scheduleAtFixedRate}, снимается с расписания навсегда, как только из
 * неё вылетело исключение, — молча, без записи в журнале. Библиотека telegrambots ловит внутри
 * своего цикла только {@code TelegramApiException}; всё прочее (сбой разбора ответа, ошибка
 * транспорта, любая {@code RuntimeException}) означает, что бот перестал получать апдейты, а
 * процесс остался жив. Ни systemd, ни {@code Restart=on-failure} такого отказа не видят, а порта
 * для health-эндпоинта мы намеренно не открываем.
 *
 * <p>Поэтому опрос заворачивается здесь: исключение гасится, пишется предупреждение, и попытка
 * повторяется после паузы.
 */
public final class GuardedPollingExecutor extends ScheduledThreadPoolExecutor {

    /** Задержка перед следующей попыткой. Свои паузы библиотека держит внутри catch, мимо которых мы проходим. */
    private static final Duration DEFAULT_PAUSE = Duration.ofSeconds(5);

    /** Сбой обычно не одиночный: без ограничения частоты непрерывный отказ забьёт journald. */
    private static final Duration DEFAULT_WARN_EVERY = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(GuardedPollingExecutor.class);

    /** Пауза вынесена в интерфейс, чтобы тесты проверяли её длительность, а не ждали её. */
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Duration pauseAfterFailure;
    private final Duration warnEvery;
    private final Clock clock;
    private final Pause pause;

    private Instant lastWarnedAt;
    private int suppressed;

    public GuardedPollingExecutor() {
        this(DEFAULT_PAUSE, DEFAULT_WARN_EVERY, Clock.systemUTC(), Thread::sleep);
    }

    GuardedPollingExecutor(Duration pauseAfterFailure, Duration warnEvery, Clock clock, Pause pause) {
        super(1, daemonThreads());
        this.pauseAfterFailure = pauseAfterFailure;
        this.warnEvery = warnEvery;
        this.clock = clock;
        this.pause = pause;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command, long initialDelay, long period, TimeUnit unit) {
        return super.scheduleAtFixedRate(guard(command), initialDelay, period, unit);
    }

    /** Библиотека сейчас пользуется fixed rate, но обе разновидности гибнут одинаково. */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return super.scheduleWithFixedDelay(guard(command), initialDelay, delay, unit);
    }

    Runnable guard(Runnable command) {
        return () -> {
            try {
                command.run();
                reportRecovery();
            } catch (Throwable failure) {
                reportFailure(failure);
                waitBeforeRetry();
            }
        };
    }

    /**
     * В журнал уходит только класс исключения. Ни текст, ни стектрейс: ответ Telegram, на котором
     * споткнулся разбор, состоит из сообщений участников семьи, и они оказались бы в journald.
     */
    private synchronized void reportFailure(Throwable failure) {
        Instant now = clock.instant();
        if (lastWarnedAt != null && Duration.between(lastWarnedAt, now).compareTo(warnEvery) < 0) {
            suppressed++;
            return;
        }
        if (suppressed > 0) {
            log.error(
                    "polling failed with {}, {} failures suppressed",
                    failure.getClass().getSimpleName(),
                    suppressed);
        } else {
            log.error("polling failed with {}", failure.getClass().getSimpleName());
        }
        lastWarnedAt = now;
        suppressed = 0;
    }

    /** Об оживании опроса нужно сказать ровно один раз: иначе в журнале остаётся только паника. */
    private synchronized void reportRecovery() {
        if (lastWarnedAt == null) {
            return;
        }
        log.info("polling recovered");
        lastWarnedAt = null;
        suppressed = 0;
    }

    private void waitBeforeRetry() {
        try {
            pause.sleep(pauseAfterFailure);
        } catch (InterruptedException e) {
            // гасят пул — восстановить флаг и уйти, а не продолжать опрос
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "telegram-polling");
            thread.setDaemon(true);
            return thread;
        };
    }
}
