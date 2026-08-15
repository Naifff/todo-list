package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.view.SchoolView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.LessonParser;
import com.familytodo.application.SchoolService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryLessonRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.DomainException;
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

/**
 * Экран расписания: {@code /school}.
 *
 * <p>⚠️ Экран заводится вместе с юзкейсом, а не после него. Ровно на этом проект уже обжигался:
 * остановка серии была написана и покрыта тестами, но её не звала ни одна кнопка, и дыра между
 * юзкейсом и ботом оставалась невидимой для сборки.
 */
class SchoolHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Суббота, 15 августа 2026, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");
    private static final int MESSAGE_ID = 321;

    private static final String WEEK =
            """
            Звонки: 08:30, 09:25, 10:30
            Пн: математика, русский, физра
            Вт: английский, -, биология
            """;

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final InMemoryLessonRepository lessons = new InMemoryLessonRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final DialogStateStore dialogs = new DialogStateStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private SchoolService school;
    private SchoolHandler handler;
    private Member mom;
    private Member kid;
    private Member otherKid;

    @BeforeEach
    void setUp() {
        FamilyService familyService = new FamilyService(families, members, tasks, notifier, clock);
        school = new SchoolService(lessons, members, families, new LessonParser(), clock);
        handler = new SchoolHandler(school, familyService, dialogs, sender);

        mom = familyService.createFamily(100000001L, 100000001L, "Мама", "Румянцевы", MOSCOW);
        kid = join(512034877L, "Петя", Role.CHILD);
        otherKid = join(512034878L, "Вася", Role.CHILD);
        sender.clear();
    }

    @Nested
    class TheScreen {

        /** Родителю сперва выбор ребёнка: расписаний в семье столько же, сколько школьников. */
        @Test
        void aParentIsAskedWhoseScheduleToOpen() {
            handler.handle(command(mom));

            assertThat(sender.texts.getFirst()).contains(Texts.SCHOOL_WHOSE);
            assertThat(labels()).contains("Петя", "Вася");
        }

        /** Ребёнку выбирать не из чего — сразу своё. */
        @Test
        void aChildSeesTheirOwnScheduleStraightAway() {
            school.replace(mom, kid.id(), WEEK);
            sender.clear();

            handler.handle(command(kid));

            assertThat(sender.texts.getFirst()).contains("математика").contains("Пн");
        }

        @Test
        void anEmptyScheduleSaysSoAndShowsHowToFillIt() {
            handler.handle(command(kid));

            assertThat(sender.texts.getFirst()).contains(Texts.SCHOOL_EMPTY);
        }

        /** Расписание показывается в том же формате, которым вводится: скопировал, поправил, послал. */
        @Test
        void theScheduleIsShownInTheFormatItIsTypedIn() {
            school.replace(mom, kid.id(), WEEK);
            sender.clear();

            handler.handle(command(kid));

            assertThat(sender.texts.getFirst())
                    .contains("Пн 08:30 математика")
                    .contains("Вт 10:30 биология");
        }
    }

    @Nested
    class Replacing {

        @Test
        void aWeekTypedInOneMessageBecomesTheSchedule() {
            handler.handle(callback(mom), replace(kid.id()));
            handler.continueDialog(text(mom, WEEK));

            assertThat(lessons.findByMember(mom.familyId(), kid.id())).hasSize(5);
        }

        /** Замена, а не дополнение: иначе после третьей правки никто не знает, что там лежит. */
        @Test
        void theNewScheduleReplacesTheOldOneWholesale() {
            school.replace(mom, kid.id(), WEEK);

            handler.handle(callback(mom), replace(kid.id()));
            handler.continueDialog(text(mom, "Пн 08:30 Математика"));

            assertThat(lessons.findByMember(mom.familyId(), kid.id())).hasSize(1);
        }

        @Test
        void theAnswerSaysHowManyWereAndHowManyAre() {
            school.replace(mom, kid.id(), WEEK);
            handler.handle(callback(mom), replace(kid.id()));
            sender.clear();

            handler.continueDialog(text(mom, "Пн 08:30 Математика"));

            assertThat(sender.texts.getLast()).contains("Было уроков: 5, стало: 1");
        }

        /** ⚠️ Непонятое сообщение не стирает прежнее расписание и не обрывает сценарий. */
        @Test
        void anUnparsedMessageChangesNothingAndKeepsAsking() {
            school.replace(mom, kid.id(), WEEK);
            handler.handle(callback(mom), replace(kid.id()));
            sender.clear();

            boolean handled = handler.continueDialog(text(mom, "чтотоне так"));

            assertThat(handled).isTrue();
            assertThat(sender.texts.getLast()).isEqualTo(Texts.SCHOOL_NOT_PARSED);
            assertThat(lessons.findByMember(mom.familyId(), kid.id())).hasSize(5);
        }

        @Test
        void clearingRemovesEverything() {
            school.replace(mom, kid.id(), WEEK);

            handler.handle(callback(mom), new CallbackData(SchoolView.PREFIX, SchoolView.CLEAR, Long.toString(kid.id())));

            assertThat(lessons.findByMember(mom.familyId(), kid.id())).isEmpty();
        }
    }

    @Nested
    class Permissions {

        /** Своё расписание ребёнок правит сам: это факт о его дне, а не поручение. */
        @Test
        void aChildMayReplaceTheirOwnSchedule() {
            handler.handle(callback(kid), replace(kid.id()));
            handler.continueDialog(text(kid, "Пн 08:30 Математика"));

            assertThat(lessons.findByMember(mom.familyId(), kid.id())).hasSize(1);
        }

        /**
         * А расписание брата — уже нет.
         *
         * <p>⚠️ Ответ «нельзя», а не «не найдено»: внутри семьи существование брата не секрет, и
         * прятать его было бы театром. Тот же порядок, что у задач, — и он требует проверять право
         * <b>до</b> чтения, иначе наружу выйдет ответ читающего запроса.
         */
        @Test
        void aChildMayNotTouchAnotherChildsSchedule() {
            assertThatThrownBy(() -> handler.handle(callback(kid), replace(otherKid.id())))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** ⚠️ И даже увидеть: чужое расписание для ребёнка не существует. */
        @Test
        void aChildDoesNotSeeAnotherChildsSchedule() {
            school.replace(mom, otherKid.id(), WEEK);

            assertThatThrownBy(() -> handler.handle(callback(kid), open(otherKid.id())))
                    .isInstanceOf(DomainException.NotFound.class);
        }
    }

    @Nested
    class Escaping {

        /** ⚠️ Предмет — пользовательский текст в HTML-сообщении: неэкранированный даёт HTTP 400. */
        @Test
        void subjectsAreEscaped() {
            school.replace(mom, kid.id(), "Пн 08:30 <b>алгебра</b> & геометрия");
            sender.clear();

            handler.handle(command(kid));

            assertThat(sender.texts.getFirst())
                    .contains("&lt;b&gt;алгебра&lt;/b&gt; &amp; геометрия")
                    .doesNotContain("<b>алгебра");
        }
    }

    // --- вспомогательное ---

    private static CallbackData replace(long memberId) {
        return CallbackData.of(SchoolView.PREFIX, SchoolView.REPLACE, memberId);
    }

    private static CallbackData open(long memberId) {
        return CallbackData.of(SchoolView.PREFIX, SchoolView.OPEN, memberId);
    }

    private List<String> labels() {
        return sender.markups.getLast().getKeyboard().stream()
                .flatMap(java.util.Collection::stream)
                .map(button -> button.getText())
                .toList();
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
        return request(member, "/school", Optional.of("school"), Optional.empty(), Optional.empty());
    }

    private BotRequest text(Member member, String written) {
        return request(member, written, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private BotRequest callback(Member member) {
        return request(member, "", Optional.empty(), Optional.of(MESSAGE_ID), Optional.of("cb-1"));
    }

    private BotRequest request(
            Member member,
            String text,
            Optional<String> command,
            Optional<Integer> messageId,
            Optional<String> callbackId) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                text,
                command,
                Optional.empty(),
                messageId,
                callbackId);
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
        private final List<String> edits = new ArrayList<>();
        private final List<InlineKeyboardMarkup> markups = new ArrayList<>();

        RecordingSender() {
            super(mock(org.telegram.telegrambots.meta.generics.TelegramClient.class));
        }

        void clear() {
            texts.clear();
            edits.clear();
            markups.clear();
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
            texts.add(html);
            markups.add(markup);
        }
    }
}
