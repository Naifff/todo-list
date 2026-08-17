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
    private final com.familytodo.application.fake.InMemoryLessonRepository lessons =
            new com.familytodo.application.fake.InMemoryLessonRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private TaskService tasks;
    private com.familytodo.application.SchoolService school;
    private AgendaHandler handler;
    private Member mom;
    private Member kid;

    @BeforeEach
    void setUp() {
        FamilyService familyService =
                new FamilyService(families, members, repository, notifier, clock);
        tasks = new TaskService(repository, members, notifier, clock);
        school =
                new com.familytodo.application.SchoolService(
                        lessons,
                        members,
                        families,
                        new com.familytodo.application.LessonParser(),
                        clock);
        handler = new AgendaHandler(tasks, familyService, school, sender, clock);

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
            // ⚠️ приписки «…и ещё N» здесь больше нет: она была тупиком — у этих дел не было
            // кнопок, то есть добраться до них было нельзя вовсе. То же решение, что в списках
            assertThat(sender.edits.getFirst()).doesNotContain("…и ещё").contains("стр. 1 из");
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

        /**
         * ⚠️ В файле ровно столько дней, сколько попросили.
         *
         * <p>Тесты до этого спрашивали «файл пришёл?», а не «что в нём»: неделя сеткой приезжала
         * одним днём, и ни один из них этого не замечал.
         */
        @Test
        void theDocumentCoversTheRequestedNumberOfDays() {
            for (int days : List.of(1, 3, 7)) {
                sender.clear();
                handler.handle(callback(mom), page(days));

                String html =
                        new String(
                                sender.documents.getFirst().png(),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertThat(countOf(html, "class=\"col\""))
                        .describedAs("дней в сетке при горизонте %s", days)
                        .isEqualTo(days);
            }
        }

        /**
         * ⚠️ Горизонт входит в имя файла.
         *
         * <p>Без него день и неделя приезжают под одним именем, и телефон открывает ранее
         * скачанный файл вместо нового. Снаружи это выглядит как «неделя присылает день», причём
         * сервер при этом отдаёт правильный файл — поэтому ни один тест содержимого не помогал.
         */
        @Test
        void everyHorizonGetsItsOwnFileName() {
            List<String> names = new ArrayList<>();
            for (int days : AgendaView.HORIZONS) {
                sender.clear();
                handler.handle(callback(mom), page(days));
                names.add(sender.documents.getFirst().fileName());
            }

            assertThat(names).doesNotHaveDuplicates();
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
            assertThat(labels).contains("Сеткой", "Списком");
        }
    }

    /**
     * Второй вид того же файла — списком. Занял место картинки: у сетки день упирается в ось и её
     * границы, у списка границ нет вовсе, и выбирает человек.
     */
    /**
     * Расписание листается страницами и нумеруется — ровно как {@code /my} и {@code /all}.
     *
     * <p>Найдено с телефона 17 августа: у строк расписания номеров не было вовсе, а под сообщением
     * стояли кнопки «1»…«12». Номер на кнопке не значил ничего — глазами его было не с чем сличить.
     * Дела без срока при этом нумеровались: одно сообщение жило по двум правилам сразу.
     */
    @Nested
    class Pages {

        @Test
        void everyLineCarriesTheNumberOfItsButton() {
            task("Первое", "2026-08-07T16:00:00Z");
            task("Второе", "2026-08-08T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            assertThat(sender.edits.getFirst())
                    .contains("1. к 19:00  Первое")
                    .contains("2. к 19:00  Второе");
            assertThat(numberButtons()).containsExactly("1", "2");
        }

        @Test
        void aPageHoldsTenAndTheRestMovesToTheNextOne() {
            for (int i = 0; i < 12; i++) {
                tasks.create(mom, kid.id(), "Дело " + i, Instant.parse("2026-08-08T16:00:00Z"));
            }
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            assertThat(sender.edits.getFirst()).contains("стр. 1 из 2");
            assertThat(numberButtons()).hasSize(10);
            assertThat(buttonLabels()).contains("Вперёд ▶").doesNotContain("◀ Назад");
        }

        /** ⚠️ Нумерация сквозная: «1» на второй странице отправляла бы искать первое дело. */
        @Test
        void theSecondPageContinuesTheNumbering() {
            for (int i = 0; i < 12; i++) {
                tasks.create(mom, kid.id(), "Дело " + i, Instant.parse("2026-08-08T16:00:00Z"));
            }
            sender.clear();

            handler.handle(callback(mom), horizonPage(7, 1));

            assertThat(sender.edits.getFirst()).contains("стр. 2 из 2").contains("11. ");
            assertThat(numberButtons()).containsExactly("11", "12");
            assertThat(buttonLabels()).contains("◀ Назад").doesNotContain("Вперёд ▶");
        }

        /** Дела без срока — те же номера, они и раньше нумеровались. */
        @Test
        void undatedTasksContinueTheSameNumbering() {
            task("Датированное", "2026-08-07T16:00:00Z");
            tasks.create(mom, kid.id(), "Без даты", (Instant) null);
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            assertThat(sender.edits.getFirst())
                    .contains("1. к 19:00  Датированное")
                    .contains("2. Без даты");
            assertThat(numberButtons()).containsExactly("1", "2");
        }

        /** Смена горизонта возвращает на первую страницу: иначе «3 дня» открывались бы пустыми. */
        @Test
        void switchingTheHorizonStartsFromTheFirstPage() {
            for (int i = 0; i < 12; i++) {
                tasks.create(mom, kid.id(), "Дело " + i, Instant.parse("2026-08-08T16:00:00Z"));
            }
            handler.handle(callback(mom), horizonPage(7, 1));
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            assertThat(sender.edits.getFirst()).contains("стр. 1 из 2");
        }

        /**
         * ⚠️ Кнопки живут в чате вечно: сообщение, отправленное до появления страниц, несёт
         * аргумент из одного числа и обязано открывать расписание, а не ошибку.
         */
        @Test
        void anOldButtonWithoutAPageStillWorks() {
            task("Дело", "2026-08-08T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            assertThat(sender.edits.getFirst()).contains("Дело");
        }

        @Test
        void aForgedPageIsNotAnError() {
            task("Дело", "2026-08-08T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), horizonPage(7, 99));

            assertThat(sender.edits.getFirst()).contains("Дело");
        }

        /** Страница обязана помещаться при любых заголовках — иначе часть дел недостижима. */
        @Test
        void aFullPageOfTheLongestTitlesStillFits() {
            for (int i = 0; i < 30; i++) {
                Task longest =
                        tasks.create(
                                mom, kid.id(), "я".repeat(200), Instant.parse("2026-08-08T16:00:00Z"));
                // худший случай целиком: к предельному заголовку добавляется предельное место
                tasks.schedule(
                        mom,
                        longest.id(),
                        Instant.parse("2026-08-08T16:00:00Z"),
                        Instant.parse("2026-08-08T17:00:00Z"),
                        "ю".repeat(100));
            }
            sender.clear();

            handler.handle(callback(mom), horizon(7));

            assertThat(sender.edits.getFirst())
                    .hasSizeLessThanOrEqualTo(HtmlEscaper.MESSAGE_LIMIT);
            assertThat(numberButtons()).hasSize(10);
        }

        private List<String> numberButtons() {
            List<String> numbers = new ArrayList<>();
            for (String label : buttonLabels()) {
                if (label.chars().allMatch(Character::isDigit)) {
                    numbers.add(label);
                }
            }
            return numbers;
        }

        private List<String> buttonLabels() {
            List<String> labels = new ArrayList<>();
            sender.markups
                    .getLast()
                    .getKeyboard()
                    .forEach(row -> row.forEach(button -> labels.add(button.getText())));
            return labels;
        }
    }

    /**
     * ⚠️ Уроки — <b>исключение</b> из правила «родитель видит календарь семьи», и исключение
     * сознательное. Найдено с телефона 16 августа: у ребёнка тридцать уроков в неделю, и в месячной
     * сетке родителя понедельник забит ими целиком — настоящее дело в тот день теряется среди
     * «Химия», «География», «История». Правило видимости задач отвечает на вопрос «что мне можно
     * увидеть», а календарь отвечает на «кто когда занят»; урок ребёнка не занимает родителя.
     *
     * <p>Расписание ребёнка родителю по-прежнему доступно — но там, где его и спрашивают:
     * {@code /school} → выбрать ребёнка.
     */
    @Nested
    class Lessons {

        @org.junit.jupiter.api.BeforeEach
        void schedule() {
            school.replace(mom, kid.id(), "Пт 10:00 Химия");
            sender.clear();
        }

        @Test
        void aParentDoesNotSeeTheLessonsOfAChild() {
            handler.handle(callback(mom), page(7));

            assertThat(text(0)).doesNotContain("Химия");
        }

        @Test
        void aChildSeesTheirOwnLessons() {
            handler.handle(callback(kid), page(7));

            assertThat(text(0)).contains("Химия");
        }

        /** Разные правила про один и тот же урок в двух видах путали бы сильнее, чем помогали. */
        @Test
        void theListViewFollowsTheSameRule() {
            handler.handle(callback(mom), list(7));
            handler.handle(callback(kid), list(7));

            assertThat(text(0)).doesNotContain("Химия");
            assertThat(text(1)).contains("Химия");
        }

        /** Дела ребёнка родитель видит по-прежнему: правило меняется только для уроков. */
        @Test
        void theTasksOfAChildStayVisibleToTheParent() {
            task("Вынести мусор", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), page(7));

            assertThat(text(0)).contains("Вынести мусор").doesNotContain("Химия");
        }

        private String text(int index) {
            return new String(
                    sender.documents.get(index).png(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Nested
    class ListPage {

        /**
         * ⚠️ Кнопки видов несут <b>текущий</b> горизонт.
         *
         * <p>Человек переключает на неделю и жмёт «Сеткой» — и получает день, если кнопка осталась
         * с прежним числом. Снаружи это выглядит как «неделя присылает день».
         */
        @Test
        void theViewButtonsCarryTheHorizonThatIsSelectedNow() {
            handler.handle(callback(mom), horizon(7));

            List<String> data = new ArrayList<>();
            sender.markups
                    .getLast()
                    .getKeyboard()
                    .forEach(row -> row.forEach(button -> data.add(button.getCallbackData())));

            assertThat(data)
                    .contains(
                            AgendaView.PREFIX + ":" + AgendaView.PAGE + ":7",
                            AgendaView.PREFIX + ":" + AgendaView.LIST + ":7");
        }

        @Test
        void listButtonSendsADocument() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), list(1));

            assertThat(sender.documents).hasSize(1);
        }

        @Test
        void theTwoViewsAreDifferentFilesAndDifferentShapes() {
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), page(1));
            handler.handle(callback(mom), list(1));

            assertThat(sender.documents.get(0).fileName())
                    .isNotEqualTo(sender.documents.get(1).fileName());
            assertThat(
                            new String(
                                    sender.documents.get(1).png(),
                                    java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("class=\"hours\"");
        }

        @Test
        void forgedHorizonIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), list(365)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(sender.documents).isEmpty();
        }

        @Test
        void everyHorizonRenders() {
            for (int days : AgendaView.HORIZONS) {
                sender.clear();
                handler.handle(callback(mom), list(days));
                assertThat(sender.documents).hasSize(1);
            }
        }
    }


    /**
     * История: то же расписание, но назад.
     *
     * <p>Видимость та же, что у календаря вперёд: родитель видит всю семью, ребёнок — только своё.
     * Статусы, наоборот, все: история из одних открытых дел бессмысленна — интересно как раз то,
     * что сделали и от чего отказались.
     */
    @Nested
    class History {

        @Test
        void thePastButtonSendsADocumentCoveringPreviousDays() {
            task("Вчерашнее", "2026-08-06T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), past(7));

            assertThat(sender.documents).hasSize(1);
            assertThat(document(0)).contains("Вчерашнее");
        }

        @Test
        void todayAndTheFutureAreNotInTheHistory() {
            task("Вчерашнее", "2026-08-06T16:00:00Z");
            task("Сегодняшнее", "2026-08-07T16:00:00Z");
            task("Завтрашнее", "2026-08-08T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), past(7));

            assertThat(document(0)).contains("Вчерашнее").doesNotContain("Завтрашнее");
        }

        /** Ради этого история и нужна: увидеть, что было сделано и от чего отказались. */
        @Test
        void closedTasksAreInTheHistory() {
            var done = tasks.create(mom, mom.id(), "Уже сделано", Instant.parse("2026-08-06T16:00:00Z"));
            tasks.complete(mom, done.id());
            sender.clear();

            handler.handle(callback(mom), past(7));

            assertThat(document(0)).contains("Уже сделано");
        }

        @Test
        void aChildSeesOnlyTheirOwnHistory() {
            tasks.create(mom, mom.id(), "Мамино вчерашнее", Instant.parse("2026-08-06T16:00:00Z"));
            tasks.create(mom, kid.id(), "Петино вчерашнее", Instant.parse("2026-08-06T17:00:00Z"));
            sender.clear();

            handler.handle(callback(kid), past(7));

            assertThat(document(0)).contains("Петино вчерашнее").doesNotContain("Мамино вчерашнее");
        }

        @Test
        void aParentSeesTheWholeFamilyHistory() {
            tasks.create(mom, kid.id(), "Петино вчерашнее", Instant.parse("2026-08-06T17:00:00Z"));
            sender.clear();

            handler.handle(callback(mom), past(7));

            assertThat(document(0)).contains("Петино вчерашнее");
        }

        /** Горизонт назад приходит от клиента и проверяется так же, как вперёд. */
        @Test
        void aForgedHorizonIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), past(365)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theFileNameSaysItIsHistory() {
            handler.handle(callback(mom), past(30));

            assertThat(sender.documents.getFirst().fileName()).contains("history").contains("30d");
        }

        /** У истории те же две формы, что и у расписания вперёд: выбирает человек. */
        @Test
        void historyComesAsAGridToo() {
            task("Вчерашнее", "2026-08-06T16:00:00Z");
            sender.clear();

            handler.handle(callback(mom), pastGrid(7));

            assertThat(sender.documents).hasSize(1);
            assertThat(document(0)).contains("Вчерашнее");
        }

        /** За месяц сетка это месячная раскладка неделями, а не ось часов на тридцать дней. */
        @Test
        void aMonthOfHistoryUsesTheMonthGridNotTheHourAxis() {
            handler.handle(callback(mom), pastGrid(30));

            assertThat(document(0)).contains("class=\"month\"");
        }

        /**
         * ⚠️ Форма обязана быть в имени файла, как и горизонт: под одним именем телефон открывает
         * ранее скачанный файл, и «сеткой» показывается вчерашним списком. Сервер при этом отдаёт
         * правильный документ, поэтому проверка содержимого такое не ловит.
         */
        @Test
        void gridAndListHistoryHaveDifferentFileNames() {
            handler.handle(callback(mom), pastGrid(7));
            String grid = sender.documents.getFirst().fileName();
            sender.clear();

            handler.handle(callback(mom), past(7));
            String list = sender.documents.getFirst().fileName();

            assertThat(grid).isNotEqualTo(list);
        }

        /**
         * ⚠️ У истории свой набор горизонтов. «За день назад» кнопкой не предлагается, но 1 есть
         * среди горизонтов вперёд — и проверка, взявшая не тот набор, пропустила бы такой запрос.
         * Видимого следа это не оставляет: файл придёт, просто за день, которого никто не просил.
         */
        @Test
        void aHorizonThatOnlyExistsGoingForwardIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), past(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> handler.handle(callback(mom), pastGrid(3)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aForgedHorizonIsRejectedForTheGridToo() {
            assertThatThrownBy(() -> handler.handle(callback(mom), pastGrid(365)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theKeyboardOffersBothFormsOfHistory() {
            handler.handle(command(mom));

            List<String> labels = new ArrayList<>();
            sender.markups
                    .getFirst()
                    .getKeyboard()
                    .forEach(row -> row.forEach(button -> labels.add(button.getText())));

            assertThat(labels)
                    .anyMatch(label -> label.contains("Неделя") && label.contains("сеткой"))
                    .anyMatch(label -> label.contains("Неделя") && label.contains("списком"))
                    .anyMatch(label -> label.contains("Месяц") && label.contains("сеткой"))
                    .anyMatch(label -> label.contains("Месяц") && label.contains("списком"));
        }

        @Test
        void theKeyboardOffersHistory() {
            handler.handle(command(mom));

            List<String> labels = new ArrayList<>();
            sender.markups
                    .getFirst()
                    .getKeyboard()
                    .forEach(row -> row.forEach(button -> labels.add(button.getText())));
            assertThat(labels).anyMatch(label -> label.contains("Неделя"));
        }

        private String document(int index) {
            return new String(
                    sender.documents.get(index).png(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // --- вспомогательное ---

    private Task task(String title, String dueAt) {
        return tasks.create(mom, kid.id(), title, Instant.parse(dueAt));
    }

    private static CallbackData list(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.LIST, Integer.toString(days));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    private static CallbackData past(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.PAST_LIST, Integer.toString(days));
    }

    private static CallbackData pastGrid(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.PAST, Integer.toString(days));
    }

    private static CallbackData page(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.PAGE, Integer.toString(days));
    }

    private static CallbackData horizon(int days) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.DAYS, Integer.toString(days));
    }

    private static CallbackData horizonPage(int days, int page) {
        return new CallbackData(AgendaView.PREFIX, AgendaView.DAYS, AgendaView.argument(days, page));
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
        public boolean sendDocument(long chatId, byte[] bytes, String fileName, String caption) {
            documents.add(new Photo(bytes, fileName, caption));
            return true;
        }

        void clear() {
            texts.clear();
            edits.clear();
            markups.clear();
            documents.clear();
        }
    }
}
