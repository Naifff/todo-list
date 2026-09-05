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
import com.familytodo.domain.Task;
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

    /**
     * Прошедшее дело остаётся в списке неделю, помеченное, и стоит на своём месте по времени.
     *
     * <p>История правила в трёх жалобах. Сначала «ролики 14.08» стояли в {@code /all} первыми и
     * выглядели ближайшим делом — их отсекли; тогда выяснилось, что перенести прошедшее событие
     * стало нечем, и его вернули, уведя в <b>конец</b> списка. ⚠️ 5 сентября вернулась та же
     * жалоба с другой стороны: с тридцатью делами хвост в конце читается как сбитая сортировка —
     * пролистав сентябрь, человек упирается в август. Теперь порядок строго по времени, а «уже
     * прошло» говорит пометка ⌛, которой в первой жалобе ещё не было.
     */
    @Nested
    class PastEvents {

        @Test
        void yesterdaysEventStaysInItsChronologicalPlace() {
            Task event = taskService.create(mom, kid.id(), "Ролики", null);
            event.schedule(
                    mom.asActor(),
                    YESTERDAY,
                    YESTERDAY.plus(java.time.Duration.ofHours(1)),
                    "цирк");
            tasks.save(event);
            sender.clear();

            taskService.create(mom, kid.id(), "Вынести мусор", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(mom, "all"));

            String text = sender.texts.getFirst();
            assertThat(text).contains("Ролики").contains("⌛");
            // вчерашнее стоит раньше завтрашнего: место в списке означает время, и только его
            assertThat(text.indexOf("Ролики")).isLessThan(text.indexOf("Вынести мусор"));
        }

        /** Сегодняшнее остаётся весь день: граница по дню, а не по моменту. */
        @Test
        void todaysEventStaysEvenAfterItEnded() {
            Task event = taskService.create(mom, kid.id(), "Зарядка", null);
            event.schedule(
                    mom.asActor(),
                    Instant.parse("2026-08-07T04:00:00Z"),
                    Instant.parse("2026-08-07T04:30:00Z"),
                    "дом");
            tasks.save(event);
            sender.clear();

            handler.handle(command(mom, "all"));

            assertThat(sender.texts.getFirst()).contains("Зарядка");
        }

        /** ⚠️ Просроченное дело со сроком остаётся: его всё ещё можно сделать. */
        @Test
        void anOverdueDeadlineStays() {
            taskService.create(mom, kid.id(), "Вынести мусор", YESTERDAY);
            sender.clear();

            handler.handle(command(mom, "all"));

            assertThat(sender.texts.getFirst()).contains("Вынести мусор").contains("❗️");
        }

        /**
         * ⚠️ Через неделю протухшее уходит из списка совсем: этого достаточно, чтобы перенести
         * дело, если оно ещё нужно, а дальше оно только копится.
         */
        @Test
        void afterAWeekTheEventLeavesTheListAltogether() {
            Instant longAgo = Instant.parse("2026-07-30T16:00:00Z"); // восемь дней назад
            Task event = taskService.create(mom, kid.id(), "Ролики", null);
            event.schedule(
                    mom.asActor(), longAgo, longAgo.plus(java.time.Duration.ofHours(1)), "цирк");
            tasks.save(event);
            taskService.create(mom, kid.id(), "Вынести мусор", TOMORROW_EVENING);
            sender.clear();

            handler.handle(command(mom, "all"));

            assertThat(sender.texts.getFirst()).contains("Вынести мусор").doesNotContain("Ролики");
        }

        /** На седьмой день оно ещё здесь: граница по дню дела, а не «примерно неделя». */
        @Test
        void onTheSeventhDayItIsStillThere() {
            Instant threeDaysAgo = Instant.parse("2026-08-01T16:00:00Z");
            Task event = taskService.create(mom, kid.id(), "Ролики", null);
            event.schedule(
                    mom.asActor(),
                    threeDaysAgo,
                    threeDaysAgo.plus(java.time.Duration.ofHours(1)),
                    "цирк");
            tasks.save(event);
            sender.clear();

            handler.handle(command(mom, "all"));

            assertThat(sender.texts.getFirst()).contains("Ролики");
        }

        /**
         * ⚠️ Просроченный срок протухает наравне с событием, и это отмена прежнего решения.
         *
         * <p>Считалось, что просроченное «вынести мусор к 19:00» всё ещё можно сделать, поэтому оно
         * остаётся навсегда. На проде 5 сентября это дало семнадцать строк середины августа —
         * «прививка ВПЧ 19.08», «Гастроинтеролог 27.08». К тому же событие от срока отличает не
         * человек, а разбор: заводя дело с одним временем, он получает срок.
         */
        @Test
        void anOldOverdueDeadlineLeavesToo() {
            taskService.create(mom, kid.id(), "Постирать рюкзаки", Instant.parse("2026-07-01T16:00:00Z"));
            sender.clear();

            handler.handle(command(mom, "all"));

            assertThat(sender.texts.getFirst()).doesNotContain("Постирать рюкзаки");
        }

        /** А свежепросроченный срок остаётся — и с восклицательным знаком. */
        @Test
        void aRecentlyOverdueDeadlineStays() {
            taskService.create(mom, kid.id(), "Забрать паспорт", Instant.parse("2026-08-05T16:00:00Z"));
            sender.clear();

            handler.handle(command(mom, "all"));

            assertThat(sender.texts.getFirst()).contains("Забрать паспорт").contains("❗️");
        }

        @Test
        void theSameHoldsForMine() {
            Task event = taskService.create(mom, kid.id(), "Ролики", null);
            event.schedule(
                    mom.asActor(),
                    YESTERDAY,
                    YESTERDAY.plus(java.time.Duration.ofHours(1)),
                    "цирк");
            tasks.save(event);
            sender.clear();

            handler.handle(command(kid, "my"));

            assertThat(sender.texts.getFirst()).contains("Ролики").contains("⌛");
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

        /**
         * Длинный список приходит страницами, а не обрезком с припиской. Прежнее «…и ещё N» было
         * тупиком: у этих дел не было кнопок, то есть добраться до них было нельзя вовсе.
         */
        @Test
        void aLongListComesInPages() {
            for (int i = 1; i <= TaskListView.PAGE_SIZE + 3; i++) {
                taskService.create(mom, kid.id(), "Дело %02d".formatted(i), TOMORROW_EVENING);
            }
            sender.clear();

            handler.handle(command(kid, "my"));

            String text = sender.texts.getFirst();
            assertThat(text).contains("1 из 2").contains("Дело 01").contains("Дело 10");
            assertThat(text).doesNotContain("Дело 11").doesNotContain("…и ещё");
        }

        /**
         * ⚠️ Ради этого страница и десять дел, а не двадцать: двадцать заголовков по 200 символов
         * дают сообщение длиннее допустимого, Telegram отвечает HTTP 400, и список не приходит
         * вовсе. Страница обязана помещаться при любых заголовках — обрезанная означала бы дела,
         * недостижимые ни с одной страницы.
         */
        @Test
        void aFullPageOfTheLongestTitlesStillFits() {
            for (int i = 0; i < TaskListView.PAGE_SIZE * 3; i++) {
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
