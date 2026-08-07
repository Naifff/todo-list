package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.longpolling.util.TelegramOkHttpClientFactory;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Опрос планируется периодической задачей — на этом держится {@link GuardedPollingExecutor}.
 *
 * <p>Охрана работает через переопределённые {@code scheduleAtFixedRate} и
 * {@code scheduleWithFixedDelay}. Если библиотека однажды перейдёт на разовую задачу, которая
 * перепланирует саму себя, переопределения перестанут вызываться — <b>без единой ошибки сборки и
 * без единой строки в журнале</b>, ровно тот способ отказа, от которого класс и защищает. Тест
 * поднимает заглушку Bot API на локальном порту и смотрит, каким методом библиотека ставит опрос.
 *
 * <p>Заглушка нужна потому, что регистрация бота начинается с реального вызова {@code
 * deleteWebhook}: без ответа на него до планирования дело не доходит.
 */
class PollingSchedulingIT {

    private static final String TOKEN = "1:test-token";

    private HttpServer botApi;
    private RecordingExecutor executor;
    private TelegramBotsLongPollingApplication application;

    @BeforeEach
    void startStub() throws IOException {
        botApi = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        botApi.createContext("/", PollingSchedulingIT::respond);
        botApi.start();

        executor = new RecordingExecutor();
        application =
                new TelegramBotsLongPollingApplication(
                        ObjectMapper::new,
                        new TelegramOkHttpClientFactory.DefaultOkHttpClientCreator(),
                        () -> executor);
    }

    @AfterEach
    void stopStub() throws Exception {
        application.close();
        executor.shutdownNow();
        botApi.stop(0);
    }

    @Test
    void libraryPollsThroughAPeriodicTask() throws Exception {
        application.registerBot(
                TOKEN,
                this::stubUrl,
                new DefaultGetUpdatesGenerator(),
                (LongPollingSingleThreadUpdateConsumer) PollingSchedulingIT::ignore);

        assertThat(executor.scheduledPeriodically.await(5, TimeUnit.SECONDS))
                .describedAs("опрос поставлен периодической задачей, охрана перехватывает его")
                .isTrue();
    }

    private TelegramUrl stubUrl() {
        return new TelegramUrl("http", "localhost", botApi.getAddress().getPort(), false);
    }

    private static void ignore(Update update) {}

    /** Ответы ровно на то, что нужно для регистрации и первого опроса. */
    private static void respond(HttpExchange exchange) throws IOException {
        String body =
                exchange.getRequestURI().getPath().endsWith("/deleteWebhook")
                        ? "{\"ok\":true,\"result\":true}"
                        : "{\"ok\":true,\"result\":[]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Запоминает не задачу, а способ её планирования: проверяем именно его. */
    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {

        private final CountDownLatch scheduledPeriodically = new CountDownLatch(1);
        private final AtomicBoolean stopPolling = new AtomicBoolean();

        private RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            scheduledPeriodically.countDown();
            return super.scheduleAtFixedRate(once(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            scheduledPeriodically.countDown();
            return super.scheduleWithFixedDelay(once(command), initialDelay, delay, unit);
        }

        /** Период у библиотеки — микросекунда; без ограничения тест залил бы заглушку запросами. */
        private Runnable once(Runnable command) {
            return () -> {
                if (stopPolling.compareAndSet(false, true)) {
                    command.run();
                }
            };
        }
    }
}
