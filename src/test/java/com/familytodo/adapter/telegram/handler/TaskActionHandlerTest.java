package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.adapter.telegram.view.TaskCardView;
import com.familytodo.adapter.telegram.view.TaskListPresenter;
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
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

class TaskActionHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
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
    private TaskActionHandler handler;

    private Member mom;
    private Member dad;
    private Member kid;
    private Member otherKid;
    private Member stranger;

    @BeforeEach
    void setUp() {
        FamilyService familyService = new FamilyService(families, members, repository, notifier, clock);
        tasks = new TaskService(repository, members, notifier, clock);
        handler =
                new TaskActionHandler(
                        tasks,
                        familyService,
                        new TaskListPresenter(tasks, familyService, sender, clock),
                        dialogs,
                        sender,
                        clock);

        mom = familyService.createFamily(100000001L, 100000001L, "Мама", "Румянцевы", MOSCOW);
        dad = join(mom, 100001L, "Папа", Role.PARENT);
        kid = join(mom, 100002L, "Петя", Role.CHILD);
        otherKid = join(mom, 100003L, "Вася", Role.CHILD);
        stranger =
                familyService.createFamily(900001L, 900001L, "Чужой", "Петровы", MOSCOW);
        sender.clear();
        notifier.clear();
    }

    @Nested
    class Buttons {

        /** Набор кнопок отражает права: отказаться может только исполнитель. */
        @Test
        void assigneeSeesDeclineButAuthorDoesNot() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(labels(task, kid)).contains("Готово", "Не могу");
            assertThat(labels(task, mom)).contains("Готово").doesNotContain("Не могу");
        }

        @Test
        void uninvolvedChildSeesNoActions() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(labels(task, otherKid)).containsExactly("← Назад");
        }

        @Test
        void closedTaskOffersReopenInsteadOfDone() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            tasks.complete(kid, task.id());
            Task closed = repository.findById(mom.familyId(), task.id()).orElseThrow();

            assertThat(labels(closed, kid)).contains("Вернуть").doesNotContain("Готово", "Не могу");
        }

        @Test
        void anyParentMayCompleteButNotReopenSomeoneElsesTask() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(labels(task, dad)).contains("Готово");
            tasks.complete(kid, task.id());
            Task closed = repository.findById(mom.familyId(), task.id()).orElseThrow();
            assertThat(labels(closed, dad)).doesNotContain("Вернуть");
        }

        /**
         * ⚠️ Из карточки вхождения нужен переход к правилу: здесь виден один день, а «больше не
         * повторять» живёт на серии. Без этой кнопки единственный доступный жест — «Удалить», и он
         * убирает не то, что человек имел в виду.
         */
        @Test
        void anOccurrenceOffersAWayToItsSeries() {
            Task occurrence = occurrence();

            assertThat(labels(occurrence, kid)).contains(Texts.TASK_TO_SERIES);
        }

        @Test
        void anOrdinaryTaskHasNoSeriesButton() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(labels(task, kid)).doesNotContain(Texts.TASK_TO_SERIES);
        }

        private List<String> labels(Task task, Member viewer) {
            return TaskCardView.keyboard(task, viewer.asActor(), TaskListView.Kind.MINE)
                    .getKeyboard()
                    .stream()
                    .flatMap(List::stream)
                    .map(InlineKeyboardButton::getText)
                    .toList();
        }
    }

    @Nested
    class Actions {

        @Test
        void doneClosesTheTaskAndRewritesTheSameMessage() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();

            handler.handle(callback(kid), action(TaskCardView.DONE, task.id()));

            assertThat(repository.findById(mom.familyId(), task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.DONE);
            assertThat(sender.edits).hasSize(1);
            assertThat(sender.texts).isEmpty();
        }

        /** Повторное нажатие — не ошибка: бот отвечает «уже отмечено», а не падает. */
        @Test
        void repeatedDoneIsReportedAsAlreadyClosed() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(kid), action(TaskCardView.DONE, task.id()));

            assertThatThrownBy(
                            () -> handler.handle(callback(kid), action(TaskCardView.DONE, task.id())))
                    .isInstanceOfSatisfying(
                            DomainException.InvalidTransition.class,
                            e -> assertThat(e.currentStatus()).isEqualTo(TaskStatus.DONE));
        }

        @Test
        void reopenReturnsTaskToWork() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(kid), action(TaskCardView.DONE, task.id()));

            handler.handle(callback(mom), action(TaskCardView.REOPEN, task.id()));

            assertThat(repository.findById(mom.familyId(), task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.OPEN);
        }

        @Test
        void backRewritesTheMessageWithTheListItCameFrom() {
            tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();

            handler.handle(callback(kid), new CallbackData(TaskCardView.PREFIX, TaskCardView.BACK, "m"));

            assertThat(sender.edits).hasSize(1);
            assertThat(sender.edits.getFirst()).contains(Texts.MINE_HEADER, "Вынести мусор");
        }
    }

    @Nested
    class DeclineFlow {

        @Test
        void asksForReasonThenSavesItAndTellsTheAuthor() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();
            notifier.clear();

            handler.handle(callback(kid), action(TaskCardView.DECLINE, task.id()));
            assertThat(sender.texts).containsExactly(Texts.ASK_DECLINE_REASON);
            assertThat(dialogs.get(kid.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingDeclineReason.class);

            sender.clear();
            handler.continueDialog(text(kid, "я на тренировке"));

            Task declined = repository.findById(mom.familyId(), task.id()).orElseThrow();
            assertThat(declined.status()).isEqualTo(TaskStatus.DECLINED);
            assertThat(declined.declineReasonOf(kid.id())).contains("я на тренировке");
            assertThat(notifier.sent())
                    .extracting(
                            FakeNotifier.Sent::kind,
                            FakeNotifier.Sent::recipientId,
                            FakeNotifier.Sent::detail)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    Kind.DECLINED, mom.id(), "я на тренировке"));
            assertThat(dialogs.get(kid.telegramUserId())).isEmpty();
        }

        /** Право проверяется до вопроса: иначе человек напишет объяснение и только потом узнает. */
        @Test
        void authorIsRefusedBeforeBeingAskedForAReason() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(mom), action(TaskCardView.DECLINE, task.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(sender.texts).isEmpty();
            assertThat(dialogs.get(mom.telegramUserId())).isEmpty();
        }

        @Test
        void emptyReasonAsksAgainWithoutClosingTheTask() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            handler.handle(callback(kid), action(TaskCardView.DECLINE, task.id()));
            sender.clear();

            handler.continueDialog(text(kid, "   "));

            assertThat(sender.texts).containsExactly(Texts.ASK_DECLINE_REASON);
            assertThat(repository.findById(mom.familyId(), task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.OPEN);
        }

        @Test
        void freeTextOutsideTheFlowIsNotClaimed() {
            assertThat(handler.continueDialog(text(kid, "просто болтаю"))).isFalse();
        }
    }

    /** Строка кнопки приходит от клиента: id чужой задачи подставляется тривиально. */
    @Nested
    class ForgedCallbackData {

        @Test
        void strangerCannotOpenTheCard() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(stranger), action(TaskCardView.CARD, task.id())))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        @Test
        void strangerCannotCompleteAndStateIsUnchanged() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(stranger), action(TaskCardView.DONE, task.id())))
                    .isInstanceOf(DomainException.NotFound.class);
            assertThat(repository.findById(mom.familyId(), task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.OPEN);
        }

        /** Своя семья: содержимое чужой задачи ребёнку не показываем даже по прямому id. */
        @Test
        void uninvolvedChildCannotReadSomeoneElsesCard() {
            Task task = tasks.create(mom, dad.id(), "Забрать посылку", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(otherKid), action(TaskCardView.CARD, task.id())))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        @Test
        void uninvolvedChildCannotCompleteEvenWithAValidId() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(otherKid), action(TaskCardView.DONE, task.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(repository.findById(mom.familyId(), task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.OPEN);
        }

        /** Кнопки «Не могу» у постороннего нет — но нажатие всё равно приходит и всё равно отвергается. */
        @Test
        void hiddenDeclineButtonIsStillRefusedWhenForged() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(dad), action(TaskCardView.DECLINE, task.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void nonexistentIdIsNotFound() {
            assertThatThrownBy(
                            () -> handler.handle(callback(kid), action(TaskCardView.CARD, 999L)))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        @Test
        void malformedReferenceIsRejected() {
            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(kid),
                                            new CallbackData(
                                                    TaskCardView.PREFIX, TaskCardView.CARD, "zzz")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- вспомогательное ---

    private Member join(Member founder, long telegramId, String name, Role role) {
        return members.save(
                Member.join(
                        members.nextId(),
                        founder.familyId(),
                        telegramId,
                        telegramId,
                        name,
                        role,
                        NOW));
    }

    private static CallbackData action(String action, long taskId) {
        return new CallbackData(
                TaskCardView.PREFIX, action, TaskRef.format(TaskListView.Kind.MINE, taskId));
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

    /** Вхождение серии: экрану важно только то, что дело помнит своё правило и день. */
    private Task occurrence() {
        Task task =
                Task.createOccurrence(
                        repository.nextId(),
                        mom.familyId(),
                        "Тренировка",
                        mom.id(),
                        List.of(new com.familytodo.domain.Assignee(kid.id(), Role.CHILD)),
                        DUE,
                        77L,
                        java.time.LocalDate.of(2026, 8, 7),
                        NOW);
        repository.saveOccurrence(task);
        return task;
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
