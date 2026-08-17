package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import com.familytodo.adapter.telegram.handler.FamilyHandler;
import com.familytodo.adapter.telegram.handler.NewTaskHandler;
import com.familytodo.adapter.telegram.handler.ShoppingHandler;
import com.familytodo.adapter.telegram.handler.TaskActionHandler;
import com.familytodo.adapter.telegram.keyboard.NewTaskKeyboards;
import com.familytodo.adapter.telegram.view.FamilyView;
import com.familytodo.adapter.telegram.view.ShoppingView;
import com.familytodo.adapter.telegram.view.TaskCardView;
import com.familytodo.adapter.telegram.view.TaskListPresenter;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.application.DueDateParser;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.InviteService;
import com.familytodo.application.SeriesService;
import com.familytodo.application.ShoppingService;
import com.familytodo.application.TaskService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryInviteRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryShoppingRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.application.fake.InMemoryTaskSeriesRepository;
import com.familytodo.adapter.telegram.handler.SeriesHandler;
import com.familytodo.adapter.telegram.view.SeriesView;
import com.familytodo.domain.InviteCodeGenerator;
import com.familytodo.domain.Member;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.Role;
import com.familytodo.domain.ShoppingList;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * В журнале только идентификаторы: ни названий дел, ни причин отказа, ни имён участников.
 *
 * <p>Журнал уезжает в journald на машине, где живёт чужой VPN, и читать его будет кто угодно с
 * доступом к серверу. Проверяем не «логи вообще» — такое утверждение непроверяемо, — а конкретные
 * сценарии: создание, закрытие, отказ, приглашение, исключение участника и остановку серии.
 *
 * <p>Уровень поднят до TRACE намеренно. В проде стоит INFO, но утечка на DEBUG — это утечка,
 * отложенная до первой попытки что-нибудь отладить на живом сервере.
 */
class LogHygieneTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    /** Маркеры выбраны так, чтобы совпадение в журнале не могло быть случайным. */
    private static final String TITLE = "Забрать-СЕКРЕТНОЕ-НАЗВАНИЕ-ДЕЛА";

    private static final String REASON = "Не-успеваю-СЕКРЕТНАЯ-ПРИЧИНА-ОТКАЗА";
    private static final String MOM_NAME = "СЕКРЕТНОЕ-ИМЯ-МАМЫ";
    private static final String KID_NAME = "СЕКРЕТНОЕ-ИМЯ-РЕБЁНКА";
    private static final String FAMILY_NAME = "СЕКРЕТНАЯ-ФАМИЛИЯ";

    /** Что человек покупает — такие же личные сведения, как и что он должен сделать. */
    private static final String GROCERY = "СЕКРЕТНАЯ-ПОКУПКА";

    private static final List<String> SECRETS =
            List.of(TITLE, REASON, MOM_NAME, KID_NAME, FAMILY_NAME, GROCERY);

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final InMemoryInviteRepository invites = new InMemoryInviteRepository();
    private final InMemoryTaskSeriesRepository seriesRepository = new InMemoryTaskSeriesRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final DialogStateStore dialogs = new DialogStateStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private Level originalLevel;

    private FamilyService familyService;
    private ShoppingHandler shopping;
    private TaskService taskService;
    private NewTaskHandler newTask;
    private TaskActionHandler actions;
    private FamilyHandler family;
    private SeriesService seriesService;
    private SeriesHandler seriesHandler;

    private Member mom;
    private Member kid;

    @BeforeEach
    void setUp() {
        familyService = new FamilyService(families, members, tasks, notifier, clock);
        shopping =
                new ShoppingHandler(
                        new ShoppingService(new InMemoryShoppingRepository(), clock),
                        sender,
                        dialogs);
        taskService = new TaskService(tasks, members, notifier, clock);
        seriesService = new SeriesService(families, seriesRepository, tasks, members, clock);
        seriesHandler =
                new SeriesHandler(
                        seriesService,
                        familyService,
                        new DueDateParser(clock),
                        dialogs,
                        sender);
        newTask =
                new NewTaskHandler(
                        taskService,
                        familyService,
                        seriesService,
                        dialogs,
                        new DueDateParser(clock),
                        sender);
        actions =
                new TaskActionHandler(
                        taskService,
                        familyService,
                        new TaskListPresenter(taskService, familyService, sender, clock),
                        new com.familytodo.adapter.telegram.view.AgendaPresenter(
                                taskService, familyService, sender, clock),
                        dialogs,
                        sender,
                        clock);
        family =
                new FamilyHandler(
                        familyService,
                        new InviteService(invites, members, new InviteCodeGenerator(), clock),
                        sender,
                        dialogs,
                        BotSettings.of("1:test-token", "FamilyTODO_bot"));

        mom = familyService.createFamily(100000001L, 100000001L, MOM_NAME, FAMILY_NAME, MOSCOW);
        kid =
                members.save(
                        Member.join(
                                members.nextId(),
                                mom.familyId(),
                                512034877L,
                                512034877L,
                                KID_NAME,
                                Role.CHILD,
                                NOW));

        originalLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logged);
        root.setLevel(originalLevel);
    }

    @Test
    void creatingATaskLogsNoTitleAndNoNames() {
        createTask();

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    @Test
    void completingATaskLogsNoTitleAndNoNames() {
        Task task = createTask();

        actions.handle(callback(kid), action(TaskCardView.DONE, task.id()));

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    /** Причина отказа — свободный текст исполнителя, и она никуда, кроме карточки, не идёт. */
    @Test
    void decliningATaskLogsNeitherTheReasonNorTheTitle() {
        Task task = createTask();

        actions.handle(callback(kid), action(TaskCardView.DECLINE, task.id()));
        actions.continueDialog(text(kid, REASON));

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    @Test
    void fillingTheShoppingListLogsNoTitles() {
        shopping.handle(
                callback(mom),
                new CallbackData(ShoppingView.PREFIX, ShoppingView.ADD, ShoppingList.FOOD.name()));
        shopping.continueDialog(text(mom, GROCERY + "\n" + GROCERY + "-второе"));

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    /**
     * Отвергнутая позиция — самый вероятный путь утечки: текст человека лежит в причине ошибки, а
     * не в шаблоне сообщения.
     */
    @Test
    void aRejectedShoppingItemLeaksNeitherTheTextNorTheReason() {
        shopping.handle(
                callback(mom),
                new CallbackData(ShoppingView.PREFIX, ShoppingView.ADD, ShoppingList.FOOD.name()));
        shopping.continueDialog(text(mom, GROCERY.repeat(20)));

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    @Test
    void issuingAnInviteLogsNoNames() {
        family.handle(callback(mom), new CallbackData(FamilyView.PREFIX, FamilyView.INVITE, "child"));

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    /** Исключение закрывает открытые дела участника — тем же путём мог бы утечь их текст. */
    @Test
    void removingAMemberLogsNeitherNamesNorTitlesOfCancelledTasks() {
        createTask();

        family.handle(callback(mom), new CallbackData(FamilyView.PREFIX, FamilyView.REMOVE_DO, String.valueOf(kid.id())));

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    /**
     * Остановка серии закрывает разом десяток вхождений — тем же путём мог бы утечь их текст, а он у
     * всех один и тот же: название правила.
     */
    @Test
    void stoppingASeriesLogsNeitherItsTitleNorNames() {
        var rule =
                seriesService.create(
                        mom,
                        List.of(kid.id()),
                        TITLE,
                        Recurrence.weekdays(),
                        LocalTime.of(18, 0),
                        null,
                        null,
                        LocalDate.of(2026, 8, 7));

        seriesHandler.handle(
                callback(mom), CallbackData.of(SeriesView.PREFIX, SeriesView.STOP_OK, rule.id()));

        assertThat(captured()).isNotEmpty();
        assertNothingLeaked();
    }

    /**
     * Проверка самой проверки: без неё тест остаётся зелёным при сломанном сборе событий и не
     * доказывает ничего.
     */
    @Test
    void aLeakWouldBeDetected() {
        LoggerFactory.getLogger(LogHygieneTest.class).info("task {} created", TITLE);

        assertThat(captured()).anyMatch(line -> line.contains(TITLE));
    }

    // --- вспомогательное ---

    private void assertNothingLeaked() {
        assertThat(captured())
                .describedAs("в журнале только идентификаторы")
                .allSatisfy(line -> assertThat(line).doesNotContain(secrets()));
    }

    private static String[] secrets() {
        return SECRETS.toArray(String[]::new);
    }

    /** Сообщение вместе с исключением: текст задачи чаще всего утекает не в шаблоне, а в причине. */
    private List<String> captured() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logged.list) {
            StringBuilder line = new StringBuilder(event.getFormattedMessage());
            for (IThrowableProxy throwable = event.getThrowableProxy();
                    throwable != null;
                    throwable = throwable.getCause()) {
                line.append(' ').append(throwable.getClassName());
                line.append(' ').append(throwable.getMessage());
                for (StackTraceElementProxy frame : throwable.getStackTraceElementProxyArray()) {
                    line.append(' ').append(frame.getSTEAsString());
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private Task createTask() {
        newTask.handle(command(mom));
        newTask.continueDialog(text(mom, TITLE));
        newTask.handle(
                callback(mom),
                new CallbackData(
                        NewTaskKeyboards.PREFIX,
                        NewTaskKeyboards.TOGGLE_ASSIGNEE,
                        String.valueOf(kid.id())));
        newTask.handle(
                callback(mom),
                new CallbackData(
                        NewTaskKeyboards.PREFIX, NewTaskKeyboards.ASSIGNEES_DONE, "0"));
        newTask.handle(
                callback(mom),
                new CallbackData(
                        NewTaskKeyboards.PREFIX, NewTaskKeyboards.DUE, NewTaskKeyboards.TODAY));
        // с задачи 30 у диалога появился шаг «Повторять?»
        newTask.handle(
                callback(mom),
                new CallbackData(
                        NewTaskKeyboards.PREFIX, NewTaskKeyboards.REPEAT, NewTaskKeyboards.ONCE));
        return tasks.find(TaskQuery.visibleTo(mom)).getFirst();
    }

    private static CallbackData action(String action, long taskId) {
        return new CallbackData(
                TaskCardView.PREFIX, action, TaskRef.format(TaskListView.Kind.MINE, taskId));
    }

    private static BotRequest command(Member member) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                "/new",
                Optional.of("new"),
                Optional.empty(),
                Optional.of(41),
                Optional.empty());
    }

    private static BotRequest callback(Member member) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(42),
                Optional.of("cb-1"));
    }

    private static BotRequest text(Member member, String text) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                text,
                Optional.empty(),
                Optional.empty(),
                Optional.of(43),
                Optional.empty());
    }

    private static final class RecordingSender extends BotSender {
        RecordingSender() {
            super(mock(org.telegram.telegrambots.meta.generics.TelegramClient.class));
        }

        @Override
        public boolean send(long chatId, String html) {
            return true;
        }

        @Override
        public boolean send(long chatId, String html, InlineKeyboardMarkup markup) {
            return true;
        }

        @Override
        public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {}

        @Override
        public void answerCallback(String callbackQueryId, String notice) {}
    }
}
