package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.application.DueDateParser;
import com.familytodo.adapter.telegram.keyboard.NewTaskKeyboards;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.TaskService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.FakeNotifier.Kind;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.SeriesService;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.application.fake.InMemoryTaskSeriesRepository;
import com.familytodo.domain.Recurrence;
import java.time.DayOfWeek;
import java.util.Set;
import com.familytodo.domain.Assignment;
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

class NewTaskHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Пятница, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final DialogStateStore dialogs = new DialogStateStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private final InMemoryTaskSeriesRepository series = new InMemoryTaskSeriesRepository();

    private SeriesService seriesService;
    private TaskService taskService;
    private NewTaskHandler handler;
    private Member mom;
    private Member kid;
    private Member dad;

    @BeforeEach
    void setUp() {
        FamilyService familyService =
                new FamilyService(families, members, tasks, notifier, clock);
        taskService = new TaskService(tasks, members, notifier, clock);
        seriesService = new SeriesService(families, series, tasks, members, clock);
        handler =
                new NewTaskHandler(
                        taskService,
                        familyService,
                        seriesService,
                        dialogs,
                        new DueDateParser(clock),
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
        dad =
                members.save(
                        Member.join(
                                members.nextId(),
                                mom.familyId(),
                                512034878L,
                                512034878L,
                                "Папа",
                                Role.PARENT,
                                NOW));
        notifier.clear();
        sender.clear();
    }

    @Nested
    class HappyPath {

        @Test
        void asksForTitleFirst() {
            handler.handle(command(mom));

            assertThat(sender.texts).containsExactly(Texts.ASK_TASK_TITLE);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingTaskTitle.class);
        }

        @Test
        void titleIsFollowedByAssigneeButtons() {
            handler.handle(command(mom));
            sender.clear();

            boolean handled = handler.continueDialog(text(mom, "Вынести мусор"));

            assertThat(handled).isTrue();
            assertThat(sender.texts).containsExactly(Texts.ASK_ASSIGNEES);
            assertThat(sender.markups).hasSize(1);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(new DialogState.ChoosingAssignees("Вынести мусор", List.of()));
        }

        @Test
        void assigneeIsFollowedByDueButtons() {
            startAndName(mom, "Вынести мусор");
            sender.clear();

            pickOne(mom, kid);

            assertThat(sender.texts).containsExactly(Texts.ASK_DUE);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(new DialogState.AwaitingDueDate("Вынести мусор", List.of(kid.id())));
        }

        @Test
        void wholeDialogCreatesTheTaskAndNotifiesAssignee() {
            startAndName(mom, "Вынести мусор");
            pickOne(mom, kid);
            sender.clear();
            notifier.clear();

            handler.handle(callback(mom), due(NewTaskKeyboards.TOMORROW));
            handler.handle(callback(mom), repeat(NewTaskKeyboards.ONCE));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.title()).isEqualTo("Вынести мусор");
            assertThat(created.assignments().getFirst().memberId()).isEqualTo(kid.id());
            assertThat(created.dueAt()).isEqualTo(Instant.parse("2026-08-08T16:00:00Z"));
            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(Kind.ASSIGNED, kid.id()));
            assertThat(dialogs.get(mom.telegramUserId())).isEmpty();
        }

        @Test
        void withoutDueDateTheTaskHasNone() {
            startAndName(mom, "Разобрать шкаф");
            pickOne(mom, kid);

            handler.handle(callback(mom), due(NewTaskKeyboards.NONE));

            assertThat(tasks.find(TaskQuery.visibleTo(mom)).getFirst().dueAt()).isNull();
        }

        /** Заявленное свойство продукта: ребёнок может попросить родителя. */
        @Test
        void childMayAssignToParent() {
            startAndName(kid, "Купить корм коту");
            pickOne(kid, mom);

            handler.handle(callback(kid), due(NewTaskKeyboards.TODAY));
            handler.handle(callback(kid), repeat(NewTaskKeyboards.ONCE));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.creatorId()).isEqualTo(kid.id());
            assertThat(created.assignments().getFirst().memberId()).isEqualTo(mom.id());
        }

        /** «Себе» — значит уже знаешь: уведомление было бы шумом. */
        @Test
        void assigningToSelfSendsNoNotification() {
            startAndName(mom, "Позвонить в поликлинику");
            pickOne(mom, mom);
            notifier.clear();

            handler.handle(callback(mom), due(NewTaskKeyboards.TODAY));
            handler.handle(callback(mom), repeat(NewTaskKeyboards.ONCE));

            assertThat(notifier.sent()).isEmpty();
            assertThat(tasks.find(TaskQuery.visibleTo(mom)).getFirst().isSelfAssigned()).isTrue();
        }
    }

    /**
     * Дело сразу на нескольких.
     *
     * <p>⚠️ Экран выбора — мультивыбор <b>всегда</b>, и это исправление, а не первоначальный
     * замысел. Сначала тап по имени выбирал одного и сразу вёл дальше, а несколько выбирались
     * отдельной кнопкой под именами — так экономилось нажатие на частом случае. С телефона это
     * не сработало ни разу: естественный жест — ткнуть в имя, он случается раньше, чем кнопку
     * замечают, а после него добавить второго уже негде. Одно лишнее нажатие дешевле, чем
     * недостижимая возможность.
     */
    @Nested
    class SeveralAssignees {

        @Test
        void pickingTwoAndPressingDoneGoesOnToTheDeadline() {
            startAndName(mom, "Отвезти Наифа к врачу");
            sender.clear();

            handler.handle(callback(mom), toggleAssignee(mom.id()));
            handler.handle(callback(mom), toggleAssignee(dad.id()));
            handler.handle(callback(mom), assigneesDone());

            assertThat(sender.texts).endsWith(Texts.ASK_DUE);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(
                            new DialogState.AwaitingDueDate(
                                    "Отвезти Наифа к врачу", List.of(mom.id(), dad.id())));
        }

        @Test
        void bothPeopleEndUpOnTheTask() {
            startAndName(mom, "Отвезти Наифа к врачу");
            handler.handle(callback(mom), toggleAssignee(mom.id()));
            handler.handle(callback(mom), toggleAssignee(dad.id()));
            handler.handle(callback(mom), assigneesDone());

            handler.handle(callback(mom), due(NewTaskKeyboards.NONE));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.assignments())
                    .extracting(Assignment::memberId)
                    .containsExactly(mom.id(), dad.id());
        }

        /** Уведомление уходит каждому, кроме самого автора: себе писать незачем. */
        @Test
        void everyoneButTheAuthorIsNotified() {
            startAndName(mom, "Отвезти Наифа к врачу");
            handler.handle(callback(mom), toggleAssignee(mom.id()));
            handler.handle(callback(mom), toggleAssignee(dad.id()));
            handler.handle(callback(mom), toggleAssignee(kid.id()));
            handler.handle(callback(mom), assigneesDone());

            handler.handle(callback(mom), due(NewTaskKeyboards.NONE));

            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(Kind.ASSIGNED, dad.id()),
                            org.assertj.core.groups.Tuple.tuple(Kind.ASSIGNED, kid.id()));
        }

        @Test
        void tappingTheSameNameTwiceUnticksIt() {
            startAndName(mom, "Отвезти Наифа к врачу");

            handler.handle(callback(mom), toggleAssignee(dad.id()));
            handler.handle(callback(mom), toggleAssignee(dad.id()));

            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(
                            new DialogState.ChoosingAssignees("Отвезти Наифа к врачу", List.of()));
        }

        /** Никого не отметили — состояние не сбрасываем: человек не передумал, а ещё не выбрал. */
        @Test
        void pressingDoneWithNobodyTickedKeepsThePicker() {
            startAndName(mom, "Отвезти Наифа к врачу");
            sender.clear();

            handler.handle(callback(mom), assigneesDone());

            assertThat(sender.edits).containsExactly(Texts.PICK_AT_LEAST_ONE_ASSIGNEE);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.ChoosingAssignees.class);
        }

        /**
         * ⚠️ Повторение доступно, только когда исполнитель один: серия хранит одного. Вопрос про
         * повторение не задаётся, но и не пропадает молча — иначе человек решит, что бот сломался.
         */
        @Test
        void aSharedTaskSkipsTheRepeatQuestionAndSaysWhy() {
            startAndName(mom, "Отвезти Наифа к врачу");
            handler.handle(callback(mom), toggleAssignee(mom.id()));
            handler.handle(callback(mom), toggleAssignee(dad.id()));
            handler.handle(callback(mom), assigneesDone());
            sender.clear();

            handler.handle(callback(mom), due(NewTaskKeyboards.TOMORROW));

            assertThat(sender.texts).hasSize(1);
            assertThat(sender.texts.getFirst())
                    .doesNotContain(Texts.ASK_REPEAT)
                    .contains(Texts.NO_REPEAT_FOR_SEVERAL)
                    .contains("Делают:");
            assertThat(series.findActive(mom.familyId())).isEmpty();
            assertThat(dialogs.get(mom.telegramUserId())).isEmpty();
        }

        /** При одном исполнителе всё остальное как прежде — вопрос про повторение на месте. */
        @Test
        void oneAssigneeStillGetsTheRepeatQuestion() {
            startAndName(mom, "Вынести мусор");

            pickOne(mom, kid);
            handler.handle(callback(mom), due(NewTaskKeyboards.TOMORROW));

            assertThat(sender.texts).endsWith(Texts.ASK_REPEAT);
        }

        /**
         * ⚠️ Тот самый провал с телефона: отметить одного и на этом закончить было нельзя —
         * добавить второго после «имя сразу ведёт дальше» уже негде. Теперь экран не уходит,
         * пока не нажали «Дальше».
         */
        @Test
        void thePickerStaysOnScreenAfterTheFirstName() {
            startAndName(mom, "Отвезти Наифа к врачу");
            sender.clear();

            handler.handle(callback(mom), toggleAssignee(mom.id()));

            // перерисовка на месте, а не новое сообщение: иначе создание дела на троих
            // оставляло бы в чате ленту из пяти одинаковых сообщений
            assertThat(sender.texts).isEmpty();
            assertThat(sender.edits).containsExactly(Texts.ASK_ASSIGNEES);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(
                            new DialogState.ChoosingAssignees(
                                    "Отвезти Наифа к врачу", List.of(mom.id())));
        }
    }

    @Nested
    class CustomDueDate {

        @Test
        void asksForTextAndParsesIt() {
            startAndName(mom, "Забрать посылку");
            pickOne(mom, kid);
            handler.handle(callback(mom), due(NewTaskKeyboards.CUSTOM));
            sender.clear();

            handler.continueDialog(text(mom, "15.08 18:30"));
            handler.handle(callback(mom), repeat(NewTaskKeyboards.ONCE));

            assertThat(tasks.find(TaskQuery.visibleTo(mom)).getFirst().dueAt())
                    .isEqualTo(Instant.parse("2026-08-15T15:30:00Z"));
        }

        /** Ошибка в формате — не повод терять уже введённое: состояние держим. */
        @Test
        void unparseableInputKeepsTheDialogAlive() {
            startAndName(mom, "Забрать посылку");
            pickOne(mom, kid);
            handler.handle(callback(mom), due(NewTaskKeyboards.CUSTOM));
            sender.clear();

            handler.continueDialog(text(mom, "25:00"));

            assertThat(sender.texts).containsExactly(Texts.SLOT_NOT_PARSED);
            assertThat(tasks.find(TaskQuery.visibleTo(mom))).isEmpty();
            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingCustomDueDate.class);
        }
    }

    @Nested
    class Validation {

        @Test
        void tooLongTitleIsRejectedWithoutAdvancing() {
            handler.handle(command(mom));
            sender.clear();

            handler.continueDialog(text(mom, "я".repeat(Task.MAX_TITLE_LENGTH + 1)));

            assertThat(sender.texts).containsExactly(Texts.TASK_TITLE_TOO_LONG);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingTaskTitle.class);
        }

        @Test
        void blankTitleAsksAgain() {
            handler.handle(command(mom));
            sender.clear();

            handler.continueDialog(text(mom, "   "));

            assertThat(sender.texts).containsExactly(Texts.ASK_TASK_TITLE);
        }
    }

    @Nested
    class ExpiredDialog {

        /** Потеря состояния допустима — но она не должна выглядеть как поломка. */
        @Test
        void assigneeChoiceWithoutStateOffersToStartOver() {
            handler.handle(callback(mom), toggleAssignee(kid.id()));

            assertThat(sender.texts).containsExactly(Texts.DIALOG_EXPIRED);
            assertThat(tasks.find(TaskQuery.visibleTo(mom))).isEmpty();
        }

        @Test
        void dueChoiceWithoutStateOffersToStartOver() {
            handler.handle(callback(mom), due(NewTaskKeyboards.TODAY));

            assertThat(sender.texts).containsExactly(Texts.DIALOG_EXPIRED);
            assertThat(tasks.find(TaskQuery.visibleTo(mom))).isEmpty();
        }

        @Test
        void newCommandStartsFreshAfterAnAbandonedDialog() {
            startAndName(mom, "Брошенная");
            sender.clear();

            handler.handle(command(mom));

            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingTaskTitle.class);
            assertThat(sender.texts).containsExactly(Texts.ASK_TASK_TITLE);
        }

        @Test
        void freeTextOutsideAnyDialogIsNotClaimed() {
            assertThat(handler.continueDialog(text(mom, "просто болтаю"))).isFalse();
        }
    }


    /**
     * Повторяющиеся дела.
     *
     * <p>Правило v1 — набор дней недели. Время берётся из выбранного срока: спрашивать его ещё раз
     * значило бы добавить шаг ради того, что человек только что назвал.
     */
    @Nested
    class Repeating {

        @Test
        void afterDueDateBotAsksAboutRepeating() {
            uptoDue();

            handler.handle(callback(mom), due(NewTaskKeyboards.TOMORROW));

            assertThat(sender.texts).containsExactly(Texts.ASK_REPEAT);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingRepeat.class);
        }

        /** «Без срока» повторять нечего: у серии обязано быть время. */
        @Test
        void undatedTaskIsCreatedWithoutAskingAboutRepeating() {
            uptoDue();

            handler.handle(callback(mom), due(NewTaskKeyboards.NONE));

            assertThat(dialogs.get(mom.telegramUserId())).isEmpty();
            assertThat(tasks.find(TaskQuery.visibleTo(mom))).hasSize(1);
            assertThat(series.findActive(mom.familyId())).isEmpty();
        }

        @Test
        void onceCreatesAPlainTaskAndNoSeries() {
            uptoRepeat();

            handler.handle(callback(mom), repeat(NewTaskKeyboards.ONCE));

            assertThat(tasks.find(TaskQuery.visibleTo(mom))).hasSize(1);
            assertThat(series.findActive(mom.familyId())).isEmpty();
        }

        @Test
        void dailyCreatesASeriesAndFillsTheHorizon() {
            uptoRepeat();

            handler.handle(callback(mom), repeat(NewTaskKeyboards.DAILY));

            assertThat(series.findActive(mom.familyId())).hasSize(1);
            assertThat(series.findActive(mom.familyId()).getFirst().recurrence())
                    .isEqualTo(Recurrence.daily());
            assertThat(tasks.find(TaskQuery.visibleTo(mom)))
                    .describedAs(
                            "горизонт заполняется сразу, а не через час; серия начинается завтра,"
                                    + " поэтому на день меньше горизонта")
                    .hasSize(SeriesService.HORIZON_DAYS - 1);
        }

        @Test
        void weekdaysCreatesAWeekdaySeries() {
            uptoRepeat();

            handler.handle(callback(mom), repeat(NewTaskKeyboards.WEEKDAYS));

            assertThat(series.findActive(mom.familyId()).getFirst().recurrence())
                    .isEqualTo(Recurrence.weekdays());
        }

        /** Время серии — время выбранного срока, а не что-то своё. */
        @Test
        void seriesKeepsTheTimeOfTheChosenDueDate() {
            uptoRepeat();

            handler.handle(callback(mom), repeat(NewTaskKeyboards.DAILY));

            assertThat(series.findActive(mom.familyId()).getFirst().startTime())
                    .isEqualTo(DueDateParser.DEFAULT_TIME);
        }

        @Test
        void customDaysOpenAPickerThatStartsEmpty() {
            uptoRepeat();

            handler.handle(callback(mom), repeat(NewTaskKeyboards.PICK_DAYS));

            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.ChoosingDays.class);
            assertThat(series.findActive(mom.familyId())).isEmpty();
        }

        @Test
        void togglingADayKeepsThePickerOpen() {
            uptoPicker();

            handler.handle(callback(mom), day(DayOfWeek.TUESDAY));

            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(
                            new DialogState.ChoosingDays(
                                    "Вынести мусор",
                                    List.of(kid.id()),
                                    deadline(tomorrow()),
                                    Set.of(DayOfWeek.TUESDAY)));
            assertThat(series.findActive(mom.familyId())).isEmpty();
        }

        @Test
        void togglingTheSameDayTwiceRemovesIt() {
            uptoPicker();
            handler.handle(callback(mom), day(DayOfWeek.TUESDAY));

            handler.handle(callback(mom), day(DayOfWeek.TUESDAY));

            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(
                            new DialogState.ChoosingDays(
                                    "Вынести мусор", List.of(kid.id()), deadline(tomorrow()), Set.of()));
        }

        @Test
        void chosenDaysBecomeTheSeriesRule() {
            uptoPicker();
            handler.handle(callback(mom), day(DayOfWeek.TUESDAY));
            handler.handle(callback(mom), day(DayOfWeek.THURSDAY));

            handler.handle(callback(mom), repeat(NewTaskKeyboards.DAYS_DONE));

            assertThat(series.findActive(mom.familyId()).getFirst().recurrence())
                    .isEqualTo(Recurrence.on(Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)));
        }

        /** Ни одного дня — не правило, а тихо мёртвая серия. Подтвердить такое нельзя. */
        @Test
        void confirmingWithoutAnyDayIsRefused() {
            uptoPicker();

            handler.handle(callback(mom), repeat(NewTaskKeyboards.DAYS_DONE));

            assertThat(series.findActive(mom.familyId())).isEmpty();
            assertThat(sender.texts).contains(Texts.PICK_AT_LEAST_ONE_DAY);
        }

        @Test
        void expiredDialogDoesNotCreateASeries() {
            uptoRepeat();
            dialogs.clear(mom.telegramUserId());

            handler.handle(callback(mom), repeat(NewTaskKeyboards.DAILY));

            assertThat(series.findActive(mom.familyId())).isEmpty();
            assertThat(sender.texts).contains(Texts.DIALOG_EXPIRED);
        }

        private void uptoDue() {
            handler.handle(command(mom));
            handler.continueDialog(text(mom, "Вынести мусор"));
            pickOne(mom, kid);
            sender.clear();
        }

        private void uptoRepeat() {
            uptoDue();
            handler.handle(callback(mom), due(NewTaskKeyboards.TOMORROW));
            sender.clear();
        }

        private void uptoPicker() {
            uptoRepeat();
            handler.handle(callback(mom), repeat(NewTaskKeyboards.PICK_DAYS));
            sender.clear();
        }
    }


    /**
     * Шаг «Время и место»: он заменил прежнюю «Свою дату» и умеет больше — интервал и место одной
     * строкой. Отдельными шагами это стоило бы двух лишних нажатий на каждом деле, включая те,
     * где никакого интервала нет.
     */
    @Nested
    class TimeAndPlace {

        @Test
        void buttonIsOfferedInsteadOfCustomDate() {
            handler.handle(command(mom));
            handler.continueDialog(text(mom, "Погулять"));
            pickOne(mom, kid);

            List<String> labels =
                    sender.markups.getLast().getKeyboard().stream()
                            .flatMap(row -> row.stream())
                            .map(button -> button.getText())
                            .toList();
            assertThat(labels).contains("Время и место").doesNotContain("Своя дата");
        }

        @Test
        void intervalBecomesOccupiedTimeAndPlace() {
            uptoSlot();

            handler.continueDialog(text(mom, "18:00-19:00 парк"));
            handler.handle(callback(mom), repeat(NewTaskKeyboards.ONCE));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.startsAt()).isEqualTo(Instant.parse("2026-08-07T15:00:00Z"));
            assertThat(created.endsAt()).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
            assertThat(created.location()).isEqualTo("парк");
            assertThat(created.dueAt())
                    .describedAs("интервал — занятое время, а не срок")
                    .isNull();
        }

        @Test
        void singleTimeStaysADeadline() {
            uptoSlot();

            handler.continueDialog(text(mom, "18:30"));
            handler.handle(callback(mom), repeat(NewTaskKeyboards.ONCE));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.dueAt()).isEqualTo(Instant.parse("2026-08-07T15:30:00Z"));
            assertThat(created.startsAt()).isNull();
        }

        /** Всё, что понимала «Своя дата», обязано пониматься и здесь. */
        @Test
        void oldCustomDateInputStillWorks() {
            uptoSlot();

            handler.continueDialog(text(mom, "15.08 18:30"));
            handler.handle(callback(mom), repeat(NewTaskKeyboards.ONCE));

            assertThat(tasks.find(TaskQuery.visibleTo(mom)).getFirst().dueAt())
                    .isEqualTo(Instant.parse("2026-08-15T15:30:00Z"));
        }

        /** Место без времени: дело без даты, но с местом — и шага повторения не будет. */
        @Test
        void placeOnlyCreatesAnUndatedTask() {
            uptoSlot();

            handler.continueDialog(text(mom, "Zoom"));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.location()).isEqualTo("Zoom");
            assertThat(created.dueAt()).isNull();
            assertThat(dialogs.get(mom.telegramUserId())).isEmpty();
        }

        /** Дело с интервалом можно сделать повторяющимся — время серии берётся из начала. */
        @Test
        void intervalCanBecomeARecurringSeries() {
            uptoSlot();
            handler.continueDialog(text(mom, "08:00-08:40 школа"));

            handler.handle(callback(mom), repeat(NewTaskKeyboards.WEEKDAYS));

            assertThat(series.findActive(mom.familyId())).hasSize(1);
            assertThat(series.findActive(mom.familyId()).getFirst().startTime())
                    .isEqualTo(java.time.LocalTime.of(8, 0));
        }

        /** Текст без времени — это место, а не ошибка: «просто Zoom» законный ввод. */
        @Test
        void plainTextBecomesAPlaceRatherThanAnError() {
            uptoSlot();

            handler.continueDialog(text(mom, "у бабушки во дворе"));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.location()).isEqualTo("у бабушки во дворе");
            assertThat(created.dueAt()).isNull();
        }

        @Test
        void unparseableInputKeepsTheDialogAlive() {
            uptoSlot();

            handler.continueDialog(text(mom, "18:99"));

            assertThat(sender.texts).contains(Texts.SLOT_NOT_PARSED);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingCustomDueDate.class);
            assertThat(tasks.find(TaskQuery.visibleTo(mom))).isEmpty();
        }

        private void uptoSlot() {
            handler.handle(command(mom));
            handler.continueDialog(text(mom, "Погулять"));
            pickOne(mom, kid);
            handler.handle(callback(mom), due(NewTaskKeyboards.CUSTOM));
            sender.clear();
        }
    }

    // --- вспомогательное ---



    private void startAndName(Member member, String title) {
        handler.handle(command(member));
        handler.continueDialog(text(member, title));
    }

    /**
     * Выбрать одного: отметить и нажать «Дальше».
     *
     * <p>Два нажатия вместо прежнего одного — цена того, что второго исполнителя видно всегда.
     * Прежний «тап по имени сразу ведёт дальше» экономил нажатие и делал редкий случай
     * недостижимым: до кнопки «Нескольким…» под именами дело не доходило.
     */
    private void pickOne(Member actor, Member assignee) {
        handler.handle(callback(actor), toggleAssignee(assignee.id()));
        handler.handle(callback(actor), assigneesDone());
    }

    private static CallbackData toggleAssignee(long memberId) {
        return CallbackData.of(
                NewTaskKeyboards.PREFIX, NewTaskKeyboards.TOGGLE_ASSIGNEE, memberId);
    }

    private static CallbackData assigneesDone() {
        return new CallbackData(NewTaskKeyboards.PREFIX, NewTaskKeyboards.ASSIGNEES_DONE, "0");
    }

    private static com.familytodo.application.DueDateParser.Plan deadline(java.time.Instant dueAt) {
        return new com.familytodo.application.DueDateParser.Plan(dueAt, null, null, null);
    }

    private java.time.Instant tomorrow() {
        return new DueDateParser(clock).tomorrow(MOSCOW);
    }

    private static CallbackData repeat(String choice) {
        return new CallbackData(NewTaskKeyboards.PREFIX, NewTaskKeyboards.REPEAT, choice);
    }

    private static CallbackData day(DayOfWeek day) {
        return new CallbackData(
                NewTaskKeyboards.PREFIX, NewTaskKeyboards.DAY, Integer.toString(day.getValue()));
    }

    private static CallbackData due(String choice) {
        return new CallbackData(NewTaskKeyboards.PREFIX, NewTaskKeyboards.DUE, choice);
    }

    private static BotRequest command(Member member) {
        return request(member, "/new", Optional.of("new"), Optional.empty());
    }

    private static BotRequest text(Member member, String text) {
        return request(member, text, Optional.empty(), Optional.empty());
    }

    private static BotRequest callback(Member member) {
        return request(member, "", Optional.empty(), Optional.of("cb-1"));
    }

    private static BotRequest request(
            Member member, String text, Optional<String> command, Optional<String> callbackId) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                text,
                command,
                Optional.empty(),
                Optional.of(1),
                callbackId);
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
        private final List<InlineKeyboardMarkup> markups = new ArrayList<>();
        /** Отдельно от texts: экран выбора исполнителей перерисовывается на месте, а не шлётся. */
        private final List<String> edits = new ArrayList<>();

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

        void clear() {
            texts.clear();
            markups.clear();
            edits.clear();
        }
    }
}
