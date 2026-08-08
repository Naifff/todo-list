package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.view.AgendaView;
import com.familytodo.adapter.telegram.view.HtmlEscaper;
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

class AgendaHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Пятница, 7 августа 2026, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository repository = new InMemoryTaskRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private TaskService tasks;
    private AgendaHandler handler;
    private Member mom;
    private Member kid;

    @BeforeEach
    void setUp() {
        FamilyService familyService =
                new FamilyService(families, members, repository, notifier, clock);
        tasks = new TaskService(repository, members, notifier, clock);
        handler = new AgendaHandler(tasks, familyService, sender, clock);

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
    class Horizon {

        /** «День» это сегодняшний день семьи, а не 24 часа от текущего момента. */
        @Test
        void oneDayShowsOnlyToday() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            task("Завтрашнее", "2026-08-08T16:00:00Z");
            sender.clear();

            handler.handle(command(mom));

            assertThat(sender.texts.getFirst())
                    .contains("Сегодняшнее")
                    .doesNotContain("Завтрашнее");
        }

        @Test
        void threeDaysReachesTheThirdDay() {
            task("Через два дня", "2026-08-09T16:00:00Z");
            task("Через три дня", "2026-08-10T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), horizon(3));

            assertThat(sender.edits.getFirst())
                    .contains("Через два дня")
                    .doesNotContain("Через три дня");
        }

        @Test
        void monthReachesFarAhead() {
            task("Через три недели", "2026-08-28T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), horizon(30));

            assertThat(sender.edits.getFirst()).contains("Через три недели");
        }

        /** Горизонт приходит от клиента: чужое число не должно превращаться в запрос на год. */
        @Test
        void forgedHorizonIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), horizon(365)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void switchingHorizonRewritesTheSameMessage() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            assertThat(sender.edits).hasSize(1);
            assertThat(sender.texts).isEmpty();
        }
    }

    @Nested
    class Grouping {

        @Test
        void groupsByDayWithHeaders() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            task("Завтрашнее", "2026-08-08T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            String text = sender.edits.getFirst();
            assertThat(text).contains("сегодня, 7 августа, пятница");
            assertThat(text).contains("завтра, 8 августа, суббота");
        }

        /** Интервал показываем целиком, срок — как «к 19:00»: это разные обещания. */
        @Test
        void showsIntervalAndDeadlineDifferently() {
            Task scheduled = task("Отвезти детей", "2026-08-07T16:00:00Z");
            tasks.schedule(
                    mom,
                    scheduled.id(),
                    Instant.parse("2026-08-07T05:00:00Z"),
                    Instant.parse("2026-08-07T05:40:00Z"),
                    "школа");
            task("Вынести мусор", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(command(mom));

            String text = sender.edits.isEmpty() ? sender.texts.getFirst() : sender.edits.getFirst();
            assertThat(text).contains("08:00–08:40").contains("школа");
            assertThat(text).contains("к 19:00");
        }

        /** Дело с интервалом стоит по началу, а не по сроку — иначе порядок дня перепутан. */
        @Test
        void ordersByStartWhenScheduled() {
            Task morning = task("Утреннее", "2026-08-07T20:00:00Z");
            tasks.schedule(mom, morning.id(), Instant.parse("2026-08-07T05:00:00Z"), null, null);
            task("Вечернее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(command(mom));

            String text = sender.texts.getFirst();
            assertThat(text.indexOf("Утреннее")).isLessThan(text.indexOf("Вечернее"));
        }

        /** «Когда-нибудь разобрать шкаф» не обещано на сегодня — отдельным блоком, а не в дне. */
        @Test
        void undatedTasksGoToTheirOwnBlock() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            tasks.create(mom, kid.id(), "Когда-нибудь", null);
            sender.clear();

            handler.handle(command(mom));

            String text = sender.texts.getFirst();
            assertThat(text).contains("Без срока");
            assertThat(text.indexOf("Сегодняшнее")).isLessThan(text.indexOf("Без срока"));
        }

        @Test
        void emptyAgendaSaysSo() {
            handler.handle(command(mom));

            assertThat(sender.texts).containsExactly(Texts.AGENDA_EMPTY);
        }
    }

    @Nested
    class Safety {

        /** Тот же лимит, что поймал список в задаче 15: месяц плотного расписания это много. */
        @Test
        void aMonthOfDenseScheduleStillFitsIntoOneMessage() {
            for (int day = 7; day < 31; day++) {
                for (int n = 0; n < 8; n++) {
                    tasks.create(
                            mom,
                            kid.id(),
                            "я".repeat(120),
                            Instant.parse(String.format("2026-08-%02dT%02d:00:00Z", day, 5 + n)));
                }
            }
            sender.clear();

            handler.handle(callback(mom), horizon(30));

            assertThat(sender.edits.getFirst())
                    .hasSizeLessThanOrEqualTo(HtmlEscaper.MESSAGE_LIMIT);
            assertThat(sender.edits.getFirst()).contains("…и ещё");
        }

        @Test
        void childSeesOnlyOwnTasksOnEveryHorizon() {
            tasks.create(mom, mom.id(), "Мамино", Instant.parse("2026-08-09T16:00:00Z"));
            tasks.create(mom, kid.id(), "Петино", Instant.parse("2026-08-09T16:00:00Z"));
            sender.clear();

            handler.handle(callback(kid), horizon(30));

            assertThat(sender.edits.getFirst()).contains("Петино").doesNotContain("Мамино");
        }

        @Test
        void escapesUserText() {
            tasks.create(
                    mom, kid.id(), "Купить <хлеб> & молоко", Instant.parse("2026-08-07T16:00:00Z"));
            sender.clear();

            handler.handle(command(mom));

            assertThat(sender.texts.getFirst())
                    .contains("Купить &lt;хлеб&gt; &amp; молоко")
                    .doesNotContain("<хлеб>");
        }

        @Test
        void keyboardOffersEveryHorizon() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(command(mom));

            assertThat(sender.markups.getFirst().getKeyboard().getFirst())
                    .hasSize(AgendaView.HORIZONS.size());
        }
    }


    /**
     * Второй вид: страница файлом.
     *
     * <p>Картинка обзорнее, страница подробнее — и живут они рядом, пока новый вид не обкатан на
     * настоящих телефонах.
     */
    @Nested
    class Page {

        @Test
        void pageButtonSendsADocumentNotAPhotoAndNotAMessage() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), page(1));

            assertThat(sender.documents).hasSize(1);
            assertThat(sender.photos).isEmpty();
            assertThat(sender.texts).isEmpty();
            assertThat(sender.documents.getFirst().png()).isNotEmpty();
        }

        /** Имя латиницей и с датой: файл ляжет в «Загрузки» рядом с прошлыми. */
        @Test
        void documentIsNamedAsAnHtmlFileWithTheDate() {
            handler.handle(callback(mom), page(7));

            assertThat(sender.documents.getFirst().fileName())
                    .endsWith(".html")
                    .contains("2026-08-07")
                    .matches("[A-Za-z0-9.\\-]+");
        }

        @Test
        void theDocumentIsTheRenderedSchedule() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), page(1));

            String html =
                    new String(
                            sender.documents.getFirst().png(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html).startsWith("<!doctype html>").contains("Сегодняшнее");
        }

        @Test
        void captionNamesTheHorizon() {
            handler.handle(callback(mom), page(7));

            assertThat(sender.documents.getFirst().caption()).contains("неделя");
        }

        /** Горизонт приходит от клиента и проверяется так же, как у списка и картинки. */
        @Test
        void forgedHorizonIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), page(365)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(sender.documents).isEmpty();
        }

        @Test
        void everyHorizonRenders() {
            for (int days : AgendaView.HORIZONS) {
                sender.clear();
                handler.handle(callback(mom), page(days));
                assertThat(sender.documents).hasSize(1);
            }
        }

        /** Дела без даты в файл входят — в отличие от картинки, где им негде быть на оси. */
        @Test
        void undatedTasksAreInTheFileItself() {
            tasks.create(mom, kid.id(), "Когда-нибудь разобрать гараж", null);
            sender.clear();

            handler.handle(callback(mom), page(1));

            String html =
                    new String(
                            sender.documents.getFirst().png(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html).contains("Когда-нибудь разобрать гараж");
        }

        /** Кнопка есть на экране расписания рядом с картинкой. */
        @Test
        void theKeyboardOffersBothViews() {
            handler.handle(command(mom));

            List<String> labels = new ArrayList<>();
            sender.markups.getFirst()
                    .getKeyboard()
                    .forEach(row -> row.forEach(button -> labels.add(button.getText())));
            assertThat(labels).contains("Картинкой", "Страницей");
        }
    }

    @Nested
    class Picture {

        @Test
        void pictureButtonSendsAPhotoNotAMessage() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), picture(1));

            assertThat(sender.photos).hasSize(1);
            assertThat(sender.texts).isEmpty();
            assertThat(sender.photos.getFirst().png()).isNotEmpty();
        }

        /** Картинка — вложение, у неё должно быть имя файла с расширением. */
        @Test
        void photoIsNamedAsAPng() {
            handler.handle(callback(mom), picture(7));

            assertThat(sender.photos.getFirst().fileName()).endsWith(".png");
        }

        @Test
        void captionNamesTheHorizon() {
            handler.handle(callback(mom), picture(7));

            assertThat(sender.photos.getFirst().caption()).contains("неделя");
        }

        /**
         * Дел без даты на календаре нет — им негде быть на оси времени. Молча их потерять нельзя:
         * человек решит, что список пуст.
         */
        @Test
        void captionSaysHowManyUndatedTasksAreNotShown() {
            tasks.create(mom, kid.id(), "Когда-нибудь разобрать гараж", null);
            tasks.create(mom, kid.id(), "И почистить чердак", null);
            sender.clear();

            handler.handle(callback(mom), picture(1));

            assertThat(sender.photos.getFirst().caption()).contains("2 дела без даты");
        }

        @Test
        void captionSaysNothingAboutUndatedWhenThereAreNone() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), picture(1));

            assertThat(sender.photos.getFirst().caption()).doesNotContain("без даты");
        }

        /** Горизонт приходит от клиента и проверяется так же, как у списка. */
        @Test
        void forgedHorizonIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), picture(365)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(sender.photos).isEmpty();
        }

        @Test
        void everyHorizonRenders() {
            for (int days : AgendaView.HORIZONS) {
                sender.clear();
                handler.handle(callback(mom), picture(days));
                assertThat(sender.photos).describedAs("горизонт %d", days).hasSize(1);
            }
        }

        @Test
        void keyboardOffersThePictureButton() {
            handler.handle(command(mom));

            assertThat(labels(sender.markups.getFirst())).contains("Картинкой");
        }

        private List<String> labels(InlineKeyboardMarkup markup) {
            return markup.getKeyboard().stream()
                    .flatMap(row -> row.stream())
                    .map(button -> button.getText())
                    .toList();
        }
    }

    // --- вспомогательное ---

    private Task task(String title, String dueAt) {
        return tasks.create(mom, kid.id(), title, Instant.parse(dueAt));
    }

    private static CallbackData picture(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.PICTURE, Integer.toString(days));
    }

    private static CallbackData page(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.PAGE, Integer.toString(days));
    }

    private static CallbackData horizon(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.DAYS, Integer.toString(days));
    }

    private static BotRequest command(Member member) {
        return build(member, Optional.of("agenda"), Optional.empty());
    }

    private static BotRequest callback(Member member) {
        return build(member, Optional.empty(), Optional.of("cb-1"));
    }

    private static BotRequest build(
            Member member, Optional<String> command, Optional<String> callbackId) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                command.map(c -> "/" + c).orElse(""),
                command,
                Optional.empty(),
                Optional.of(5),
                callbackId);
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
        private final List<String> edits = new ArrayList<>();
        private final List<InlineKeyboardMarkup> markups = new ArrayList<>();
        private final List<Photo> photos = new ArrayList<>();
        private final List<Photo> documents = new ArrayList<>();

        record Photo(byte[] png, String fileName, String caption) {}

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
            markups.add(markup);
            return true;
        }

        @Override
        public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
            edits.add(html);
            markups.add(markup);
        }

        @Override
        public boolean sendPhoto(long chatId, byte[] png, String fileName, String caption) {
            photos.add(new Photo(png, fileName, caption));
            return true;
        }

        @Override
        public boolean sendDocument(long chatId, byte[] bytes, String fileName, String caption) {
            documents.add(new Photo(bytes, fileName, caption));
            return true;
        }

        void clear() {
            texts.clear();
            edits.clear();
            markups.clear();
            photos.clear();
            documents.clear();
        }
    }
}
