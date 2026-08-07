package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.DueDateParser;
import com.familytodo.adapter.telegram.keyboard.NewTaskKeyboards;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.TaskService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.FakeNotifier.Kind;
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

    private TaskService taskService;
    private NewTaskHandler handler;
    private Member mom;
    private Member kid;

    @BeforeEach
    void setUp() {
        FamilyService familyService =
                new FamilyService(families, members, tasks, notifier, clock);
        taskService = new TaskService(tasks, members, notifier, clock);
        handler =
                new NewTaskHandler(
                        taskService,
                        familyService,
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
            assertThat(sender.texts).containsExactly(Texts.ASK_ASSIGNEE);
            assertThat(sender.markups).hasSize(1);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(new DialogState.AwaitingAssignee("Вынести мусор"));
        }

        @Test
        void assigneeIsFollowedByDueButtons() {
            startAndName(mom, "Вынести мусор");
            sender.clear();

            handler.handle(callback(mom), assignee(kid.id()));

            assertThat(sender.texts).containsExactly(Texts.ASK_DUE);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .contains(new DialogState.AwaitingDueDate("Вынести мусор", kid.id()));
        }

        @Test
        void wholeDialogCreatesTheTaskAndNotifiesAssignee() {
            startAndName(mom, "Вынести мусор");
            handler.handle(callback(mom), assignee(kid.id()));
            sender.clear();
            notifier.clear();

            handler.handle(callback(mom), due(NewTaskKeyboards.TOMORROW));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.title()).isEqualTo("Вынести мусор");
            assertThat(created.assignee().memberId()).isEqualTo(kid.id());
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
            handler.handle(callback(mom), assignee(kid.id()));

            handler.handle(callback(mom), due(NewTaskKeyboards.NONE));

            assertThat(tasks.find(TaskQuery.visibleTo(mom)).getFirst().dueAt()).isNull();
        }

        /** Заявленное свойство продукта: ребёнок может попросить родителя. */
        @Test
        void childMayAssignToParent() {
            startAndName(kid, "Купить корм коту");
            handler.handle(callback(kid), assignee(mom.id()));

            handler.handle(callback(kid), due(NewTaskKeyboards.TODAY));

            Task created = tasks.find(TaskQuery.visibleTo(mom)).getFirst();
            assertThat(created.creatorId()).isEqualTo(kid.id());
            assertThat(created.assignee().memberId()).isEqualTo(mom.id());
        }

        /** «Себе» — значит уже знаешь: уведомление было бы шумом. */
        @Test
        void assigningToSelfSendsNoNotification() {
            startAndName(mom, "Позвонить в поликлинику");
            handler.handle(callback(mom), assignee(mom.id()));
            notifier.clear();

            handler.handle(callback(mom), due(NewTaskKeyboards.TODAY));

            assertThat(notifier.sent()).isEmpty();
            assertThat(tasks.find(TaskQuery.visibleTo(mom)).getFirst().isSelfAssigned()).isTrue();
        }
    }

    @Nested
    class CustomDueDate {

        @Test
        void asksForTextAndParsesIt() {
            startAndName(mom, "Забрать посылку");
            handler.handle(callback(mom), assignee(kid.id()));
            handler.handle(callback(mom), due(NewTaskKeyboards.CUSTOM));
            sender.clear();

            handler.continueDialog(text(mom, "15.08 18:30"));

            assertThat(tasks.find(TaskQuery.visibleTo(mom)).getFirst().dueAt())
                    .isEqualTo(Instant.parse("2026-08-15T15:30:00Z"));
        }

        /** Ошибка в формате — не повод терять уже введённое: состояние держим. */
        @Test
        void unparseableInputKeepsTheDialogAlive() {
            startAndName(mom, "Забрать посылку");
            handler.handle(callback(mom), assignee(kid.id()));
            handler.handle(callback(mom), due(NewTaskKeyboards.CUSTOM));
            sender.clear();

            handler.continueDialog(text(mom, "когда-нибудь"));

            assertThat(sender.texts).containsExactly(Texts.DUE_NOT_PARSED);
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
            handler.handle(callback(mom), assignee(kid.id()));

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

    // --- вспомогательное ---

    private void startAndName(Member member, String title) {
        handler.handle(command(member));
        handler.continueDialog(text(member, title));
    }

    private static CallbackData assignee(long memberId) {
        return CallbackData.of(NewTaskKeyboards.PREFIX, NewTaskKeyboards.ASSIGNEE, memberId);
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

        RecordingSender() {
            super(mock(org.telegram.telegrambots.meta.generics.TelegramClient.class));
        }

        @Override
        public void send(long chatId, String html) {
            texts.add(html);
        }

        @Override
        public void send(long chatId, String html, InlineKeyboardMarkup markup) {
            texts.add(html);
            markups.add(markup);
        }

        void clear() {
            texts.clear();
            markups.clear();
        }
    }
}
