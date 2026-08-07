package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

class TaskListHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Пятница, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private static final Instant TODAY_EVENING = Instant.parse("2026-08-07T16:00:00Z");
    private static final Instant TOMORROW_EVENING = Instant.parse("2026-08-08T16:00:00Z");
    private static final Instant YESTERDAY = Instant.parse("2026-08-06T16:00:00Z");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private TaskService taskService;
    private TaskListHandler handler;
    private Member mom;
    private Member kid;

    @BeforeEach
    void setUp() {
        FamilyService familyService = new FamilyService(families, members, tasks, notifier, clock);
        taskService = new TaskService(tasks, members, notifier, clock);
        handler =
                new TaskListHandler(
                        new com.familytodo.adapter.telegram.view.TaskListPresenter(
                                taskService, familyService, sender, clock),
                        sender);

        mom = familyService.createFamily(100000001L, 100000001L, "Мама", "Румянцевы", MOSCOW);
        kid =
                members.save(
                        Member.join(
                                members.nextId(),
                                mom.familyId(),
                                512034877L,
                                512034877L,
                                "Петя",
                                Role.CHILD,
                                NOW));
        sender.clear();
    }

    @Nested
    class OneMessage {

        /** Список из N задач не должен превращаться в N сообщений. */
        @Test
        void sendsExactlyOneMessageRegardlessOfSize() {
            for (int i = 0; i < 7; i++) {
                taskService.create(mom, kid.id(), "Дело " + i, TOMORROW_EVENING);
            }
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts).hasSize(1);
            assertThat(sender.texts.getFirst()).contains("Дело 0", "Дело 6");
        }

        @Test
        void listsWhatWasAskedOfMe() {
            taskService.create(mom, kid.id(), "Вынести мусор", TOMORROW_EVENING);
            taskService.create(kid, mom.id(), "Купить корм", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts.getFirst())
                    .contains(Texts.MINE_HEADER, "Вынести мусор", "от Мама")
                    .doesNotContain("Купить корм");
        }

        @Test
        void listsWhatIAskedOfOthers() {
            taskService.create(mom, kid.id(), "Вынести мусор", TOMORROW_EVENING);
            taskService.create(kid, mom.id(), "Купить корм", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(kid, "assigned"));

            assertThat(sender.texts.getFirst())
                    .contains(Texts.REQUESTED_HEADER, "Купить корм", "Мама")
                    .doesNotContain("Вынести мусор");
        }
    }

    @Nested
    class AllTasks {

        @Test
        void parentSeesEverything() {
            taskService.create(mom, kid.id(), "Вынести мусор", TOMORROW_EVENING);
            taskService.create(kid, mom.id(), "Купить корм", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(mom, "all"));

            assertThat(sender.texts.getFirst())
                    .contains(Texts.ALL_HEADER, "Вынести мусор", "Купить корм", "Мама → Петя");
        }

        /** Отказ, а не список из двух своих дел под заголовком «Все дела семьи». */
        @Test
        void childIsRefusedExplicitly() {
            taskService.create(mom, kid.id(), "Вынести мусор", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(kid, "all"));

            assertThat(sender.texts).containsExactly(Texts.ALL_IS_FOR_PARENTS);
        }
    }

    @Nested
    class Rendering {

        @Test
        void marksOverdueTasks() {
            taskService.create(mom, kid.id(), "Просроченное", YESTERDAY);
            taskService.create(mom, kid.id(), "Свежее", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(kid, "my"));

            String text = sender.texts.getFirst();
            assertThat(text).contains("❗️");
            assertThat(text.lines().filter(l -> l.contains("❗️")).findFirst().orElseThrow())
                    .contains("Просроченное")
                    .doesNotContain("Свежее");
        }

        @Test
        void showsRelativeDatesForTodayAndTomorrow() {
            taskService.create(mom, kid.id(), "Сегодняшнее", TODAY_EVENING);
            taskService.create(mom, kid.id(), "Завтрашнее", TOMORROW_EVENING);
            taskService.create(mom, kid.id(), "Далёкое", Instant.parse("2026-08-15T15:30:00Z"));
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts.getFirst())
                    .contains("сегодня 19:00", "завтра 19:00", "15.08 18:30");
        }

        @Test
        void showsTasksWithoutDueDate() {
            taskService.create(mom, kid.id(), "Когда-нибудь", null);
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts.getFirst()).contains("без срока");
        }

        /** Название пользовательское: неэкранированный текст — это HTTP 400, а не кривая вёрстка. */
        @Test
        void escapesTaskTitles() {
            taskService.create(mom, kid.id(), "Купить *хлеб* <срочно> & молоко", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts.getFirst())
                    .contains("Купить *хлеб* &lt;срочно&gt; &amp; молоко")
                    .doesNotContain("<срочно>");
        }

        /** Полноценной пагинации нет: курсор в 64 байтах и устаревание страниц дороже пользы. */
        @Test
        void truncatesLongListsWithACount() {
            for (int i = 0; i < TaskListView.MAX_ITEMS + 3; i++) {
                taskService.create(mom, kid.id(), "Дело " + i, TOMORROW_EVENING);
            }
            sender.clear();

            handler.handle(command(kid, "my"));

            String text = sender.texts.getFirst();
            assertThat(text).contains("…и ещё 3");
            assertThat(text).doesNotContain("Дело " + (TaskListView.MAX_ITEMS + 2));
        }

        @Test
        void fitsIntoTheTelegramMessageLimit() {
            for (int i = 0; i < TaskListView.MAX_ITEMS + 5; i++) {
                taskService.create(mom, kid.id(), "я".repeat(200), TOMORROW_EVENING);
            }
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts.getFirst()).hasSizeLessThanOrEqualTo(4096);
        }
    }

    @Nested
    class Empty {

        @Test
        void mineHasItsOwnWording() {
            handler.handle(command(kid, "my"));

            assertThat(sender.texts).containsExactly(Texts.MINE_EMPTY);
        }

        @Test
        void requestedHasItsOwnWording() {
            handler.handle(command(kid, "assigned"));

            assertThat(sender.texts).containsExactly(Texts.REQUESTED_EMPTY);
        }

        @Test
        void allHasItsOwnWording() {
            handler.handle(command(mom, "all"));

            assertThat(sender.texts).containsExactly(Texts.ALL_EMPTY);
        }

        @Test
        void closedTasksLeaveTheList() {
            var task = taskService.create(mom, kid.id(), "Вынести мусор", TOMORROW_EVENING);
            taskService.complete(kid, task.id());
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts).containsExactly(Texts.MINE_EMPTY);
        }
    }

    private static BotRequest command(Member member, String command) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                "/" + command,
                Optional.of(command),
                Optional.empty(),
                Optional.of(1),
                Optional.empty());
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();

        RecordingSender() {
            super(mock(org.telegram.telegrambots.meta.generics.TelegramClient.class));
        }

        @Override
        public boolean send(long chatId, String html) {
            texts.add(html);
            return true;
        }

        @Override
        public boolean send(long chatId, String html, InlineKeyboardMarkup markup) {
            texts.add(html);
            return true;
        }

        void clear() {
            texts.clear();
        }
    }
}
