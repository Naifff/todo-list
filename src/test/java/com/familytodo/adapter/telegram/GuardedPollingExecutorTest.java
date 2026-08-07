package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Цикл long polling не должен умирать молча.
 *
 * <p>Библиотека отправляет опрос через {@code ScheduledExecutorService.scheduleAtFixedRate} и ловит
 * внутри только {@code TelegramApiException}. Всё остальное — сбой разбора ответа, ошибка okhttp,
 * любая {@code RuntimeException} — вылетает наружу, и планировщик <b>навсегда снимает задачу с
 * расписания, не написав ни строчки</b>. Процесс при этом жив, systemd доволен, бот молчит:
 * {@code Restart=on-failure} такой отказ не видит в принципе.
 */
class GuardedPollingExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final Duration PAUSE = Duration.ofSeconds(5);
    private static final Duration WARN_EVERY = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingPause pause = new RecordingPause();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GuardedPollingExecutor.class);

    private GuardedPollingExecutor executor;

    @BeforeEach
    void setUp() {
        logged.start();
        logger.addAppender(logged);
        executor = new GuardedPollingExecutor(PAUSE, WARN_EVERY, clock, pause);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
        executor.shutdownNow();
    }

    @Nested
    class Survival {

        @Test
        void periodicTaskKeepsRunningAfterAnException() throws Exception {
            CountDownLatch ran = new CountDownLatch(3);

            executor.scheduleAtFixedRate(
                    () -> {
                        ran.countDown();
                        throw new IllegalStateException("boom");
                    },
                    0,
                    1,
                    TimeUnit.MILLISECONDS);

            assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        }

        /** То же самое для второго периодического метода: библиотека вправе перейти на него. */
        @Test
        void fixedDelayTaskKeepsRunningAfterAnException() throws Exception {
            CountDownLatch ran = new CountDownLatch(3);

            executor.scheduleWithFixedDelay(
                    () -> {
                        ran.countDown();
                        throw new IllegalStateException("boom");
                    },
                    0,
                    1,
                    TimeUnit.MILLISECONDS);

            assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        }

        /**
         * Барьер: показывает отказ, ради которого класс существует. Если этот тест однажды позеленеет
         * иначе — значит, JDK изменил поведение и охрану можно снимать.
         */
        @Test
        void plainExecutorSilentlyStopsAfterTheFirstException() throws Exception {
            ScheduledThreadPoolExecutor plain = new ScheduledThreadPoolExecutor(1);
            AtomicInteger runs = new AtomicInteger();

            plain.scheduleAtFixedRate(
                    () -> {
                        runs.incrementAndGet();
                        throw new IllegalStateException("boom");
                    },
                    0,
                    1,
                    TimeUnit.MILLISECONDS);
            Thread.sleep(200);
            plain.shutdownNow();

            assertThat(runs).hasValue(1);
        }
    }

    @Nested
    class Pacing {

        /**
         * Библиотека ставит период в одну микросекунду и держит паузы сама, внутри своих catch. Наша
         * охрана эти catch обходит, поэтому паузу после сбоя обязана держать она — иначе первый же
         * неожиданный сбой превращается в непрерывный долбёж api.telegram.org.
         */
        @Test
        void everyFailureIsFollowedByAPause() {
            Runnable guarded = executor.guard(failing("boom"));

            guarded.run();
            guarded.run();

            assertThat(pause.recorded()).containsExactly(PAUSE, PAUSE);
        }

        @Test
        void successfulPollIsNotDelayed() {
            Runnable guarded = executor.guard(() -> {});

            guarded.run();

            assertThat(pause.recorded()).isEmpty();
        }
    }

    @Nested
    class Reporting {

        @Test
        void repeatedFailuresWarnOnlyOncePerInterval() {
            Runnable guarded = executor.guard(failing("boom"));

            for (int i = 0; i < 20; i++) {
                guarded.run();
            }

            assertThat(messages()).hasSize(1);
        }

        @Test
        void nextIntervalReportsHowManyFailuresWereSuppressed() {
            Runnable guarded = executor.guard(failing("boom"));
            for (int i = 0; i < 5; i++) {
                guarded.run();
            }

            clock.advance(WARN_EVERY.plusSeconds(1));
            guarded.run();

            assertThat(messages()).hasSize(2);
            assertThat(messages().get(1)).contains("4");
        }

        /** Опрос ожил — об этом стоит сказать один раз, иначе в журнале остаётся только паника. */
        @Test
        void recoveryIsReported() {
            executor.guard(failing("boom")).run();

            executor.guard(() -> {}).run();

            assertThat(messages()).hasSize(2);
            assertThat(messages().get(1)).contains("recovered");
        }

        @Test
        void successAfterRecoveryIsSilent() {
            Runnable ok = executor.guard(() -> {});

            ok.run();
            ok.run();

            assertThat(messages()).isEmpty();
        }

        /**
         * В сообщении сбоя — только класс исключения. Текст и стектрейс запрещены: ответ Telegram, на
         * котором споткнулся разбор, содержит сообщения пользователей, и они утекли бы в journald.
         */
        @Test
        void failureIsReportedWithoutTheExceptionMessage() {
            executor.guard(failing("unexpected token in {\"text\":\"Купить молоко\"}")).run();

            assertThat(messages().getFirst())
                    .contains("IllegalStateException")
                    .doesNotContain("Купить молоко");
            assertThat(logged.list.getFirst().getThrowableProxy()).isNull();
        }
    }

    private static Runnable failing(String message) {
        return () -> {
            throw new IllegalStateException(message);
        };
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static final class RecordingPause implements GuardedPollingExecutor.Pause {
        private final BlockingQueue<Duration> recorded = new ArrayBlockingQueue<>(64);

        @Override
        public void sleep(Duration duration) {
            recorded.offer(duration);
        }

        List<Duration> recorded() {
            return List.copyOf(recorded);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
