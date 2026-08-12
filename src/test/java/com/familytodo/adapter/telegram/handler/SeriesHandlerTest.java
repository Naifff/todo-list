package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.view.SeriesView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.SeriesService;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.application.fake.InMemoryTaskSeriesRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.Role;
import com.familytodo.domain.TaskSeries;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Экран повторяющихся дел: {@code /series}.
 *
 * <p>Появился из вопроса с телефона «как удалить дело, которое циклическое?». Остановка серии была
 * написана и покрыта тестами application-слоя, но её не звала ни одна кнопка: тесты дёргали метод
 * напрямую, и дыра между юзкейсом и ботом осталась невидимой для сборки.
 */
class SeriesHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Пятница, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final LocalTime AT_SIX = LocalTime.of(18, 0);
    private static final int MESSAGE_ID = 777;

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final InMemoryTaskSeriesRepository series = new InMemoryTaskSeriesRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private SeriesService seriesService;
    private SeriesHandler handler;
    private Member mom;
    private Member dad;
    private Member kid;
    private Member stranger;

    @BeforeEach
    void setUp() {
        FamilyService familyService = new FamilyService(families, members, tasks, notifier, clock);
        seriesService = new SeriesService(families, series, tasks, members, clock);
        handler = new SeriesHandler(seriesService, familyService, sender);

        mom = familyService.createFamily(100000001L, 100000001L, "Мама", "Румянцевы", MOSCOW);
        dad = join(512034878L, "Папа", Role.PARENT);
        kid = join(512034877L, "Петя", Role.CHILD);
        stranger =
                familyService.createFamily(900000001L, 900000001L, "Чужой", "Соседи", MOSCOW);
        sender.clear();
    }

    @Nested
    class TheList {

        @Test
        void withoutSeriesTheScreenSaysSoInsteadOfShowingNothing() {
            handler.handle(command(mom));

            assertThat(sender.texts).hasSize(1);
            assertThat(sender.texts.getFirst()).contains(Texts.SERIES_EMPTY);
        }

        @Test
        void aSeriesIsShownWithItsRuleTimeAndAssignees() {
            training();

            handler.handle(command(mom));

            assertThat(sender.texts.getFirst())
                    .contains("Тренировка")
                    .contains("по будням")
                    .contains("18:00")
                    .contains("Петя");
        }

        /**
         * ⚠️ Видимость та же, что у задач: ребёнок видит только те правила, где он исполнитель или
         * автор. До этого экрана {@code findActive(familyId)} не звали ниоткуда, и отсутствие
         * фильтра ничего не ломало — оно стало утечкой ровно здесь.
         */
        @Test
        void aChildSeesOnlyTheSeriesThatConcernHim() {
            training(); // Петя исполнитель
            seriesService.create(
                    mom,
                    List.of(dad.id()),
                    "Бассейн",
                    Recurrence.daily(),
                    LocalTime.of(7, 0),
                    null,
                    null,
                    TODAY);

            handler.handle(command(kid));

            assertThat(sender.texts.getFirst()).contains("Тренировка").doesNotContain("Бассейн");
        }

        @Test
        void aParentSeesEverySeriesOfTheFamily() {
            training();
            seriesService.create(
                    mom,
                    List.of(dad.id()),
                    "Бассейн",
                    Recurrence.daily(),
                    LocalTime.of(7, 0),
                    null,
                    null,
                    TODAY);

            handler.handle(command(dad));

            assertThat(sender.texts.getFirst()).contains("Тренировка").contains("Бассейн");
        }
    }

    @Nested
    class TheCard {

        @Test
        void tappingASeriesOpensItsCardInTheSameMessage() {
            TaskSeries training = training();

            handler.handle(callback(mom), open(training.id()));

            assertThat(sender.texts).isEmpty();
            assertThat(sender.edits).hasSize(1);
            assertThat(sender.edits.getFirst()).contains("Тренировка").contains("по будням");
        }

        @Test
        void backReturnsToTheList() {
            TaskSeries training = training();

            handler.handle(callback(mom), new CallbackData(SeriesView.PREFIX, SeriesView.BACK, "l"));

            assertThat(sender.edits).hasSize(1);
            assertThat(sender.edits.getFirst()).contains(Texts.SERIES_HEADER);
        }

        /**
         * ⚠️ {@code callback_data} — недоверенный ввод: кнопка чужой серии не показывалась, но
         * подделать её строку ничего не мешает. Ответ обязан быть «не найдено», а не чужая карточка.
         */
        @Test
        void aSeriesFromAnotherFamilyLooksLikeItDoesNotExist() {
            TaskSeries training = training();

            assertThatThrownBy(() -> handler.handle(callback(stranger), open(training.id())))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        /** Ребёнку чужое правило не показывается и по прямому номеру. */
        @Test
        void aChildCannotOpenASeriesHeIsNotPartOf() {
            TaskSeries pool =
                    seriesService.create(
                            mom,
                            List.of(dad.id()),
                            "Бассейн",
                            Recurrence.daily(),
                            LocalTime.of(7, 0),
                            null,
                            null,
                            TODAY);

            assertThatThrownBy(() -> handler.handle(callback(kid), open(pool.id())))
                    .isInstanceOf(DomainException.NotFound.class);
        }
    }

    @Nested
    class Stopping {

        /** Остановка убирает будущие дела у всей семьи — переспросить дешевле, чем восстанавливать. */
        @Test
        void stopAsksForConfirmationInsteadOfStoppingRightAway() {
            TaskSeries training = training();

            handler.handle(callback(mom), stop(training.id()));

            assertThat(sender.edits.getFirst()).contains(Texts.SERIES_STOP_CONFIRM);
            assertThat(seriesService.active(mom)).hasSize(1);
        }

        @Test
        void confirmingRemovesTheSeriesAndSaysHowManyTasksWentAway() {
            TaskSeries training = training();
            int materialised = tasks.find(everythingOpenOf(mom)).size();
            assertThat(materialised).isPositive();

            handler.handle(callback(mom), confirmStop(training.id()));

            assertThat(seriesService.active(mom)).isEmpty();
            assertThat(sender.edits.getLast())
                    .contains(Texts.SERIES_STOPPED)
                    .contains(String.valueOf(materialised));
            assertThat(tasks.find(everythingOpenOf(mom))).isEmpty();
        }

        /**
         * ⚠️ Право проверяется заново, а не кнопкой: исполнитель — не распорядитель. Петя ходит на
         * тренировку, но отменяет её тот, кто договаривался.
         */
        @Test
        void theChildTheSeriesIsAboutStillMayNotStopIt() {
            TaskSeries training = training();

            assertThatThrownBy(() -> handler.handle(callback(kid), confirmStop(training.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(seriesService.active(mom)).hasSize(1);
        }

        /** Родитель распоряжается делом ребёнка — то же правило, что у правки задачи. */
        @Test
        void anotherParentMayStopTheSeriesOfAChild() {
            TaskSeries training = training();

            handler.handle(callback(dad), confirmStop(training.id()));

            assertThat(seriesService.active(mom)).isEmpty();
        }
    }

    @Nested
    class Escaping {

        /**
         * ⚠️ Название серии — пользовательский текст в HTML-сообщении. Неэкранированное даёт HTTP
         * 400, то есть экран не приходит вовсе.
         */
        @Test
        void aSeriesTitleWithMarkupIsEscaped() {
            seriesService.create(
                    mom,
                    List.of(kid.id()),
                    "<b>тренировка</b> & бассейн",
                    Recurrence.weekdays(),
                    AT_SIX,
                    Duration.ofHours(1),
                    null,
                    TODAY);

            handler.handle(command(mom));

            assertThat(sender.texts.getFirst())
                    .contains("&lt;b&gt;тренировка&lt;/b&gt; &amp; бассейн")
                    .doesNotContain("<b>тренировка");
        }
    }

    // --- вспомогательное ---

    private TaskSeries training() {
        return seriesService.create(
                mom,
                List.of(kid.id()),
                "Тренировка",
                Recurrence.weekdays(),
                AT_SIX,
                Duration.ofHours(1),
                "Спортшкола",
                TODAY);
    }

    private static CallbackData open(long seriesId) {
        return CallbackData.of(SeriesView.PREFIX, SeriesView.OPEN, seriesId);
    }

    private static CallbackData stop(long seriesId) {
        return CallbackData.of(SeriesView.PREFIX, SeriesView.STOP, seriesId);
    }

    private static CallbackData confirmStop(long seriesId) {
        return CallbackData.of(SeriesView.PREFIX, SeriesView.STOP_OK, seriesId);
    }

    private static TaskQuery everythingOpenOf(Member viewer) {
        return TaskQuery.visibleTo(viewer);
    }

    private Member join(long telegramUserId, String name, Role role) {
        return members.save(
                Member.join(
                        members.nextId(),
                        mom.familyId(),
                        telegramUserId,
                        telegramUserId,
                        name,
                        role,
                        NOW));
    }

    private BotRequest command(Member member) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                "/series",
                Optional.of("series"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private BotRequest callback(Member member) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(MESSAGE_ID),
                Optional.of("callback-1"));
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
        private final List<String> edits = new ArrayList<>();

        RecordingSender() {
            super(mock(org.telegram.telegrambots.meta.generics.TelegramClient.class));
        }

        void clear() {
            texts.clear();
            edits.clear();
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

        @Override
        public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
            edits.add(html);
        }
    }
}
