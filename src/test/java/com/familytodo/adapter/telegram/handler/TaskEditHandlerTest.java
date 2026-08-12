package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.application.DueDateParser;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.adapter.telegram.view.TaskEditView;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.FakeNotifier.Kind;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.DomainException;
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

class TaskEditHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Пятница, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-08T16:00:00Z");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository repository = new InMemoryTaskRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final DialogStateStore dialogs = new DialogStateStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private TaskService tasks;
    private TaskEditHandler handler;

    private Member mom;
    private Member dad;
    private Member kid;
    private Member stranger;

    @BeforeEach
    void setUp() {
        FamilyService familyService =
                new FamilyService(families, members, repository, notifier, clock);
        tasks = new TaskService(repository, members, notifier, clock);
        handler =
                new TaskEditHandler(
                        tasks, familyService, dialogs, new DueDateParser(clock), sender, clock);

        mom = familyService.createFamily(100000001L, 100000001L, "Мама", "Румянцевы", MOSCOW);
        dad = join(100001L, "Папа", Role.PARENT);
        kid = join(100002L, "Петя", Role.CHILD);
        stranger = familyService.createFamily(900001L, 900001L, "Чужой", "Петровы", MOSCOW);
        sender.clear();
        notifier.clear();
    }

    @Nested
    class Title {

        @Test
        void authorChangesIt() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.TITLE, task.id()));

            handler.continueDialog(text(mom, "Вынести мусор и бумагу"));

            assertThat(reload(task).title()).isEqualTo("Вынести мусор и бумагу");
        }

        @Test
        void tooLongIsRejectedAndTheOldOneSurvives() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.TITLE, task.id()));
            sender.clear();

            handler.continueDialog(text(mom, "я".repeat(Task.MAX_TITLE_LENGTH + 1)));

            assertThat(sender.texts).containsExactly(Texts.TASK_TITLE_TOO_LONG);
            assertThat(reload(task).title()).isEqualTo("Вынести мусор");
        }
    }

    @Nested
    class DueDate {

        @Test
        void authorChangesItWithAButton() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.DUE, task.id()));

            handler.handle(callback(mom), choice(TaskEditView.SET_DUE, "today"));

            assertThat(reload(task).dueAt()).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
        }

        @Test
        void dueDateCanBeCleared() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.DUE, task.id()));

            handler.handle(callback(mom), choice(TaskEditView.SET_DUE, "none"));

            assertThat(reload(task).dueAt()).isNull();
        }

        @Test
        void customDateIsParsed() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.DUE, task.id()));
            handler.handle(callback(mom), choice(TaskEditView.SET_DUE, "custom"));

            handler.continueDialog(text(mom, "15.08 18:30"));

            assertThat(reload(task).dueAt()).isEqualTo(Instant.parse("2026-08-15T15:30:00Z"));
        }

        @Test
        void unparseableCustomDateKeepsTheDialog() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.DUE, task.id()));
            handler.handle(callback(mom), choice(TaskEditView.SET_DUE, "custom"));
            sender.clear();

            handler.continueDialog(text(mom, "когда-нибудь"));

            assertThat(sender.texts).containsExactly(Texts.DUE_NOT_PARSED);
            assertThat(reload(task).dueAt()).isEqualTo(DUE);
        }

        @Test
        void choiceWithoutStateOffersToStartOver() {
            handler.handle(callback(mom), choice(TaskEditView.SET_DUE, "today"));

            assertThat(sender.texts).containsExactly(Texts.DIALOG_EXPIRED);
        }
    }

    /**
     * «Время и место» в карточке. Тестов у этого экрана не было вовсе — и ровно в нём жила
     * ошибка: разбирал он другим парсером, который дат не знает, поэтому «27.08» уезжало в место.
     */
    @Nested
    class WhenAndWhere {

        @Test
        void aBareDateBecomesTheDeadline() {
            Task task = tasks.create(mom, kid.id(), "Забрать посылку", DUE);
            handler.handle(callback(mom), edit(TaskEditView.SLOT, task.id()));

            handler.continueDialog(text(mom, "27.08"));

            assertThat(reload(task).dueAt()).isEqualTo(Instant.parse("2026-08-27T16:00:00Z"));
            assertThat(reload(task).location()).isNull();
        }

        /**
         * ⚠️ У дела с интервалом одна лишь дата означает перенос, а не «поставить срок рядом».
         *
         * <p>Календарь ставит дело по началу интервала: если сдвинуть только срок, дело осталось
         * бы на прежнем дне, и перенос выглядел бы как «ничего не произошло».
         */
        @Test
        void aBareDateMovesAnExistingIntervalToThatDay() {
            Task task = tasks.create(mom, kid.id(), "Тренировка", DUE);
            tasks.schedule(
                    mom,
                    task.id(),
                    Instant.parse("2026-08-08T15:00:00Z"),
                    Instant.parse("2026-08-08T16:30:00Z"),
                    "бассейн");
            handler.handle(callback(mom), edit(TaskEditView.SLOT, task.id()));

            handler.continueDialog(text(mom, "27.08"));

            Task moved = reload(task);
            assertThat(moved.startsAt()).isEqualTo(Instant.parse("2026-08-27T15:00:00Z"));
            assertThat(moved.endsAt()).isEqualTo(Instant.parse("2026-08-27T16:30:00Z"));
            assertThat(moved.location()).isEqualTo("бассейн");
        }

        /** Названо только время — срок дела остаётся. Стирать его молча значило бы потерять дату. */
        @Test
        void anIntervalKeepsTheExistingDeadline() {
            Task task = tasks.create(mom, kid.id(), "Тренировка", DUE);
            handler.handle(callback(mom), edit(TaskEditView.SLOT, task.id()));

            handler.continueDialog(text(mom, "18:00-19:00 бассейн"));

            Task updated = reload(task);
            assertThat(updated.dueAt()).isEqualTo(DUE);
            assertThat(updated.location()).isEqualTo("бассейн");
            assertThat(updated.startsAt()).isEqualTo(Instant.parse("2026-08-08T15:00:00Z"));
        }

        /** Названо только место — время не трогаем. */
        @Test
        void aPlaceAloneKeepsTheTime() {
            Task task = tasks.create(mom, kid.id(), "Тренировка", DUE);
            tasks.schedule(
                    mom,
                    task.id(),
                    Instant.parse("2026-08-08T15:00:00Z"),
                    Instant.parse("2026-08-08T16:30:00Z"),
                    "бассейн");
            handler.handle(callback(mom), edit(TaskEditView.SLOT, task.id()));

            handler.continueDialog(text(mom, "школа"));

            Task updated = reload(task);
            assertThat(updated.location()).isEqualTo("школа");
            assertThat(updated.startsAt()).isEqualTo(Instant.parse("2026-08-08T15:00:00Z"));
        }

        /** Минус убирает всё разом — единственный способ очистить. */
        @Test
        void aDashClearsTimeAndPlace() {
            Task task = tasks.create(mom, kid.id(), "Тренировка", DUE);
            tasks.schedule(
                    mom,
                    task.id(),
                    Instant.parse("2026-08-08T15:00:00Z"),
                    Instant.parse("2026-08-08T16:30:00Z"),
                    "бассейн");
            handler.handle(callback(mom), edit(TaskEditView.SLOT, task.id()));

            handler.continueDialog(text(mom, "-"));

            Task updated = reload(task);
            assertThat(updated.startsAt()).isNull();
            assertThat(updated.location()).isNull();
        }
    }

    @Nested
    class Assignee {

        /** Ровно то, ради чего фича: дело было на одном, стало на двоих. */
        @Test
        void tappingAnUncheckedNameAddsThemToTheTask() {
            Task task = tasks.create(mom, kid.id(), "Отвезти к врачу", DUE);
            handler.handle(callback(mom), edit(TaskEditView.WHO, task.id()));
            notifier.clear();

            handler.handle(callback(mom), choice(TaskEditView.SET_WHO, Long.toString(dad.id())));

            assertThat(reload(task).assignments())
                    .extracting(Assignment::memberId)
                    .containsExactly(kid.id(), dad.id());
            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(tuple(Kind.ASSIGNED, dad.id()));
        }

        @Test
        void tappingACheckedNameTakesThemOff() {
            Task task =
                    tasks.create(mom, List.of(kid.id(), dad.id()), "Отвезти к врачу", DUE);
            handler.handle(callback(mom), edit(TaskEditView.WHO, task.id()));
            notifier.clear();

            handler.handle(callback(mom), choice(TaskEditView.SET_WHO, Long.toString(kid.id())));

            assertThat(reload(task).assignments())
                    .extracting(Assignment::memberId)
                    .containsExactly(dad.id());
            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(tuple(Kind.UNASSIGNED, kid.id()));
        }

        /**
         * Передать дело другому — два нажатия вместо одного: включить нового, выключить прежнего.
         *
         * <p>Уведомления при этом те же, что и раньше: с прежнего сняли, на нового положили. Молча
         * переложить просьбу по-прежнему нельзя.
         */
        @Test
        void handingTheTaskOverIsTwoTaps() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.WHO, task.id()));
            notifier.clear();

            handler.handle(callback(mom), choice(TaskEditView.SET_WHO, Long.toString(dad.id())));
            handler.handle(callback(mom), choice(TaskEditView.SET_WHO, Long.toString(kid.id())));

            assertThat(reload(task).assignments())
                    .extracting(Assignment::memberId)
                    .containsExactly(dad.id());
            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactlyInAnyOrder(
                            tuple(Kind.ASSIGNED, dad.id()), tuple(Kind.UNASSIGNED, kid.id()));
        }

        @Test
        void addedAssigneeRoleIsPickedUp() {
            Task task = tasks.create(mom, dad.id(), "Забрать посылку", DUE);
            handler.handle(callback(mom), edit(TaskEditView.WHO, task.id()));

            handler.handle(callback(mom), choice(TaskEditView.SET_WHO, Long.toString(kid.id())));

            assertThat(reload(task).assignments())
                    .filteredOn(assignment -> assignment.memberId() == kid.id())
                    .singleElement()
                    .extracting(Assignment::role)
                    .isEqualTo(Role.CHILD);
        }

        /** Последнего снять нельзя: дело без исполнителя — не просьба, а запись в никуда. */
        @Test
        void theLastAssigneeCannotBeTappedOff() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(mom), edit(TaskEditView.WHO, task.id()));
            notifier.clear();

            handler.handle(callback(mom), choice(TaskEditView.SET_WHO, Long.toString(kid.id())));

            assertThat(reload(task).assignments())
                    .extracting(Assignment::memberId)
                    .containsExactly(kid.id());
            assertThat(notifier.sent()).isEmpty();
        }
    }

    @Nested
    class Permissions {

        @Test
        void parentMayEditAChildsTask() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            handler.handle(callback(dad), edit(TaskEditView.TITLE, task.id()));
            handler.continueDialog(text(dad, "Вынести мусор до обеда"));

            assertThat(reload(task).title()).isEqualTo("Вынести мусор до обеда");
        }

        /** Самое тонкое правило матрицы: родитель не старше другого родителя. */
        @Test
        void parentMayNotEditAnotherParentsTask() {
            Task task = tasks.create(dad, mom.id(), "Забрать посылку", DUE);
            Member granny = join(100003L, "Бабушка", Role.PARENT);

            assertThatThrownBy(
                            () -> handler.handle(callback(granny), edit(TaskEditView.TITLE, task.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Право проверяется до вопроса: иначе человек напишет название и лишь потом узнает. */
        @Test
        void assigneeIsRefusedBeforeBeingAskedForANewTitle() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();

            assertThatThrownBy(
                            () -> handler.handle(callback(kid), edit(TaskEditView.TITLE, task.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(sender.texts).isEmpty();
            assertThat(dialogs.get(kid.telegramUserId())).isEmpty();
        }
    }

    @Nested
    class Deletion {

        @Test
        void confirmationIsAskedFirst() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();

            handler.handle(callback(mom), edit(TaskEditView.DELETE, task.id()));

            assertThat(sender.edits.getFirst()).contains("Удалить дело безвозвратно?");
            assertThat(repository.findById(mom.familyId(), task.id())).isPresent();
        }

        @Test
        void confirmedDeletionErasesTheRow() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            handler.handle(callback(mom), edit(TaskEditView.DELETE_OK, task.id()));

            assertThat(repository.findById(mom.familyId(), task.id())).isEmpty();
        }

        /**
         * ⚠️ У вхождения вопрос другой, потому что и происходит другое: правило остаётся, уходит
         * один день. Прежнее «удалить безвозвратно» здесь было обманом дважды — дело возвращалось
         * через час, а «безвозвратно» обещало обратное.
         */
        @Test
        void anOccurrenceIsAskedAboutDifferently() {
            Task occurrence = occurrence();
            sender.clear();

            handler.handle(callback(mom), edit(TaskEditView.DELETE, occurrence.id()));

            assertThat(sender.edits.getFirst())
                    .contains(Texts.DELETE_OCCURRENCE_CONFIRM)
                    .doesNotContain(Texts.DELETE_CONFIRM);
        }

        @Test
        void assigneeCannotDelete() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(kid), edit(TaskEditView.DELETE_OK, task.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(repository.findById(mom.familyId(), task.id())).isPresent();
        }

        /** Подделанный id чужой семьи не должен ничего стирать. */
        @Test
        void strangerCannotDeleteWithAForgedReference() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(stranger),
                                            edit(TaskEditView.DELETE_OK, task.id())))
                    .isInstanceOf(DomainException.NotFound.class);
            assertThat(repository.findById(mom.familyId(), task.id())).isPresent();
        }

        @Test
        void strangerCannotOpenTheEditMenu() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(stranger), edit(TaskEditView.MENU, task.id())))
                    .isInstanceOf(DomainException.NotFound.class);
        }
    }

    // --- вспомогательное ---

    /** Вхождение серии, собранное напрямую: экрану важно только то, что оно помнит своё правило. */
    private Task occurrence() {
        Task task =
                Task.createOccurrence(
                        repository.nextId(),
                        mom.familyId(),
                        "Тренировка",
                        mom.id(),
                        java.util.List.of(new com.familytodo.domain.Assignee(kid.id(), Role.CHILD)),
                        DUE,
                        77L,
                        java.time.LocalDate.of(2026, 8, 7),
                        NOW);
        repository.saveOccurrence(task);
        return task;
    }

    private Task reload(Task task) {
        return repository.findById(mom.familyId(), task.id()).orElseThrow();
    }

    private Member join(long telegramId, String name, Role role) {
        return members.save(
                Member.join(
                        members.nextId(),
                        mom.familyId(),
                        telegramId,
                        telegramId,
                        name,
                        role,
                        NOW));
    }

    private static CallbackData edit(String action, long taskId) {
        return new CallbackData(
                TaskEditView.PREFIX, action, TaskRef.format(TaskListView.Kind.MINE, taskId));
    }

    private static CallbackData choice(String action, String argument) {
        return new CallbackData(TaskEditView.PREFIX, action, argument);
    }

    private static BotRequest callback(Member member) {
        return build(member, "", Optional.of("cb-1"));
    }

    private static BotRequest text(Member member, String text) {
        return build(member, text, Optional.empty());
    }

    private static BotRequest build(Member member, String text, Optional<String> callbackId) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                text,
                Optional.empty(),
                Optional.empty(),
                Optional.of(11),
                callbackId);
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
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
            return true;
        }

        @Override
        public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
            edits.add(html);
        }

        void clear() {
            texts.clear();
            edits.clear();
        }
    }
}
