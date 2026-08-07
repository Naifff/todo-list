package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.familytodo.application.TaskService;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

class TelegramNotifierTest {

    private static final long FAMILY = 1L;
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-08T16:00:00Z");

    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository repository = new InMemoryTaskRepository();
    private final ScriptedSender sender = new ScriptedSender();

    private TelegramNotifier notifier;
    private TaskService tasks;

    private Member mom;
    private Member kid;

    @BeforeEach
    void setUp() {
        notifier = new TelegramNotifier(sender, members, Clock.fixed(NOW, ZoneOffset.UTC));
        tasks = new TaskService(repository, members, notifier, Clock.fixed(NOW, ZoneOffset.UTC));

        mom = members.save(Member.join(10L, FAMILY, 100L, 1100L, "Мама", Role.PARENT, NOW));
        kid = members.save(Member.join(12L, FAMILY, 102L, 1102L, "Петя", Role.CHILD, NOW));
    }

    @Nested
    class Routing {

        @Test
        void assignmentGoesToTheAssignee() {
            tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(sender.chats).containsExactly(kid.privateChatId());
            assertThat(sender.messages.getFirst()).contains("Тебя просят", "Вынести мусор");
        }

        @Test
        void completionGoesToTheAuthor() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();

            tasks.complete(kid, task.id());

            assertThat(sender.chats).containsExactly(mom.privateChatId());
            assertThat(sender.messages.getFirst()).contains("Петя", "сделал");
        }

        @Test
        void declineGoesToTheAuthorWithTheReason() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();

            tasks.decline(kid, task.id(), "я на тренировке");

            assertThat(sender.chats).containsExactly(mom.privateChatId());
            assertThat(sender.messages.getFirst()).contains("не сможет", "я на тренировке");
        }

        @Test
        void selfAssignedTaskNotifiesNobody() {
            tasks.create(mom, mom.id(), "Позвонить в поликлинику", DUE);

            assertThat(sender.messages).isEmpty();
        }

        /** Название и причина — пользовательский текст: неэкранированный даёт HTTP 400. */
        @Test
        void escapesUserText() {
            tasks.create(mom, kid.id(), "Купить <хлеб> & молоко", DUE);

            assertThat(sender.messages.getFirst())
                    .contains("Купить &lt;хлеб&gt; &amp; молоко")
                    .doesNotContain("<хлеб>");
        }
    }

    @Nested
    class BlockedBot {

        /** Иначе каждое напоминание годами уходит в никуда, тратя запрос и место в логе. */
        @Test
        void blockedRecipientIsMarkedOnce() {
            sender.blocked.add(kid.privateChatId());

            tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(members.findById(FAMILY, kid.id()).orElseThrow().blockedBot()).isTrue();
            assertThat(sender.messages).hasSize(1);
        }

        @Test
        void nothingIsSentToSomeoneWhoAlreadyBlockedTheBot() {
            kid.markBotBlocked();
            members.save(kid);

            tasks.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(sender.messages).isEmpty();
        }

        @Test
        void aBlockedAssigneeDoesNotStopTheAuthorsNotification() {
            Task task = tasks.create(mom, kid.id(), "Вынести мусор", DUE);
            sender.clear();
            sender.blocked.add(mom.privateChatId());

            tasks.complete(kid, task.id());

            assertThat(members.findById(FAMILY, mom.id()).orElseThrow().blockedBot()).isTrue();
        }
    }

    @Nested
    class Resilience {

        /** Рассылка идёт циклами: падение на одном получателе не должно отменять остальных. */
        @Test
        void doesNotPropagateFailures() {
            sender.failing.add(kid.privateChatId());

            assertThatCode(() -> tasks.create(mom, kid.id(), "Вынести мусор", DUE))
                    .doesNotThrowAnyException();
        }

        @Test
        void oneFailureDoesNotStopTheRest() {
            sender.failing.add(kid.privateChatId());
            Member dad = members.save(Member.join(11L, FAMILY, 101L, 1101L, "Папа", Role.PARENT, NOW));

            notifier.taskAssigned(kid, sample());
            notifier.taskAssigned(dad, sample());

            assertThat(sender.chats).contains(dad.privateChatId());
        }

        private Task sample() {
            return Task.create(
                    1L, FAMILY, "Вынести мусор", mom.id(), new Assignee(kid.id(), Role.CHILD), DUE, NOW);
        }
    }

    /** Отправитель, которому можно задать, какие чаты заблокированы, а какие падают. */
    private static final class ScriptedSender extends BotSender {
        private final List<Long> chats = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private final Set<Long> blocked = new HashSet<>();
        private final Set<Long> failing = new HashSet<>();

        ScriptedSender() {
            super(mock(org.telegram.telegrambots.meta.generics.TelegramClient.class));
        }

        @Override
        public boolean send(long chatId, String html) {
            return send(chatId, html, null);
        }

        @Override
        public boolean send(long chatId, String html, InlineKeyboardMarkup markup) {
            chats.add(chatId);
            messages.add(html);
            if (failing.contains(chatId)) {
                throw new IllegalStateException("сеть отвалилась");
            }
            return !blocked.contains(chatId);
        }

        void clear() {
            chats.clear();
            messages.clear();
        }
    }
}
