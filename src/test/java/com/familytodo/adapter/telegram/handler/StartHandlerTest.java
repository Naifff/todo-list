package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.keyboard.TimezoneKeyboard;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.InviteService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryInviteRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.Invite;
import com.familytodo.domain.InviteCodeGenerator;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import java.time.Clock;
import java.time.Duration;
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

class StartHandlerTest {

    private static final long NEWCOMER = 512034877L;
    private static final long CHAT = 512034877L;
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryInviteRepository invites = new InMemoryInviteRepository();
    private final DialogStateStore dialogs = new DialogStateStore();
    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingSender sender = new RecordingSender();

    private FamilyService familyService;
    private InviteService inviteService;
    private StartHandler handler;

    @BeforeEach
    void setUp() {
        familyService =
                new FamilyService(
                        families,
                        members,
                        new InMemoryTaskRepository(),
                        new FakeNotifier(),
                        clock);
        inviteService =
                new InviteService(invites, members, new InviteCodeGenerator(), clock);
        handler =
                new StartHandler(familyService, inviteService, members, dialogs, sender, true);
    }

    /**
     * Создание новых семей выключено.
     *
     * <p>Приглашения при этом обязаны работать: закрыта регистрация, а не вход. Иначе выключатель
     * отрезал бы от бота и тех, кому ссылку уже прислали.
     */
    @Nested
    class WhenFamilyCreationIsClosed {

        private StartHandler closed;

        @BeforeEach
        void closeRegistration() {
            closed =
                    new StartHandler(
                            familyService, inviteService, members, dialogs, sender, false);
        }

        @Test
        void newcomerWithoutAnInviteIsToldToAskForALink() {
            closed.handle(start(NEWCOMER, null, Optional.empty()));

            assertThat(sender.texts).containsExactly(Texts.FAMILY_CREATION_CLOSED);
        }

        /** Ни одной семьи не появилось и никого ни о чём не спросили. */
        @Test
        void newcomerWithoutAnInviteStartsNoDialogAndNoFamily() {
            closed.handle(start(NEWCOMER, null, Optional.empty()));

            assertThat(dialogs.get(NEWCOMER)).isEmpty();
            assertThat(families.findAll()).isEmpty();
        }

        /** Свободный текст после отказа тоже ничего не создаёт: диалога нет, отвечать нечему. */
        @Test
        void typingAfterTheRefusalCreatesNothing() {
            closed.handle(start(NEWCOMER, null, Optional.empty()));

            assertThat(closed.continueDialog(text(NEWCOMER, "Ивановы"))).isFalse();
            assertThat(families.findAll()).isEmpty();
        }

        @Test
        void anInviteStillLetsSomeoneIn() {
            String code = inviteService.issue(founder(), Role.CHILD).code();

            closed.handle(start(NEWCOMER, null, Optional.of("inv_" + code)));

            assertThat(members.findByTelegramUserId(NEWCOMER)).isPresent();
        }
    }

    @Nested
    class CreatingAFamily {

        @Test
        void newcomerIsAskedForFamilyName() {
            handler.handle(start(NEWCOMER, null, Optional.empty()));

            assertThat(sender.texts).containsExactly(Texts.ASK_FAMILY_NAME);
            assertThat(dialogs.get(NEWCOMER))
                    .containsInstanceOf(DialogState.AwaitingFamilyName.class);
        }

        @Test
        void nameIsFollowedByTimezoneButtons() {
            handler.handle(start(NEWCOMER, null, Optional.empty()));
            sender.clear();

            boolean handled = handler.continueDialog(text(NEWCOMER, "Ивановы"));

            assertThat(handled).isTrue();
            assertThat(sender.markups).hasSize(1);
            assertThat(dialogs.get(NEWCOMER))
                    .contains(new DialogState.AwaitingTimezone("Ивановы"));
        }

        /** Название пользовательское — оно попадает в сообщение и обязано экранироваться. */
        @Test
        void escapesFamilyNameInTheReply() {
            handler.handle(start(NEWCOMER, null, Optional.empty()));
            sender.clear();

            handler.continueDialog(text(NEWCOMER, "Иванов<ы> & Ко"));

            assertThat(sender.texts.getFirst())
                    .contains("Иванов&lt;ы&gt; &amp; Ко")
                    .doesNotContain("<ы>");
        }

        @Test
        void tooLongNameIsRejectedWithoutAdvancing() {
            handler.handle(start(NEWCOMER, null, Optional.empty()));
            sender.clear();

            handler.continueDialog(text(NEWCOMER, "и".repeat(61)));

            assertThat(sender.texts).containsExactly(Texts.FAMILY_NAME_TOO_LONG);
            assertThat(dialogs.get(NEWCOMER))
                    .containsInstanceOf(DialogState.AwaitingFamilyName.class);
        }

        @Test
        void timezoneChoiceCreatesFamilyWithFounderAsParent() {
            createFamily("Ивановы", moscowIndex());

            Member founder = members.findByTelegramUserId(NEWCOMER).orElseThrow();
            assertThat(founder.role()).isEqualTo(Role.PARENT);
            assertThat(founder.privateChatId()).isEqualTo(CHAT);
            assertThat(families.findById(founder.familyId()).orElseThrow().timezone())
                    .isEqualTo(MOSCOW);
            assertThat(families.findById(founder.familyId()).orElseThrow().name())
                    .isEqualTo("Ивановы");
        }

        @Test
        void dialogIsClearedAfterCreation() {
            createFamily("Ивановы", moscowIndex());

            assertThat(dialogs.get(NEWCOMER)).isEmpty();
        }

        /** Индекс приходит от клиента: подделанный не должен ничего создавать. */
        @Test
        void forgedTimezoneIndexCreatesNothing() {
            handler.handle(start(NEWCOMER, null, Optional.empty()));
            handler.continueDialog(text(NEWCOMER, "Ивановы"));
            sender.clear();

            handler.handle(callback(NEWCOMER), CallbackData.of("s", "tz", 999));

            assertThat(members.findByTelegramUserId(NEWCOMER)).isEmpty();
            assertThat(sender.texts).containsExactly(Texts.ASK_TIMEZONE_AGAIN);
        }

        /** Диалог мог протухнуть по TTL — тогда начинаем сначала, а не падаем. */
        @Test
        void timezoneWithoutDialogRestartsOnboarding() {
            handler.handle(callback(NEWCOMER), CallbackData.of("s", "tz", moscowIndex()));

            assertThat(members.findByTelegramUserId(NEWCOMER)).isEmpty();
            assertThat(sender.texts).containsExactly(Texts.ASK_FAMILY_NAME);
        }
    }

    @Nested
    class JoiningByInvite {

        @Test
        void newcomerJoinsWithRoleFromInvite() {
            Member mom = founder();
            Invite invite = inviteService.issue(mom, Role.CHILD);
            sender.clear();

            handler.handle(start(NEWCOMER, null, Optional.of("inv_" + invite.code())));

            Member joined = members.findByTelegramUserId(NEWCOMER).orElseThrow();
            assertThat(joined.role()).isEqualTo(Role.CHILD);
            assertThat(joined.familyId()).isEqualTo(mom.familyId());
            assertThat(joined.privateChatId()).isEqualTo(CHAT);
            assertThat(sender.texts.getFirst()).contains(Texts.MAIN_MENU);
        }

        @Test
        void expiredInviteCreatesNoMember() {
            Member mom = founder();
            Invite invite = inviteService.issue(mom, Role.CHILD);
            clock.advance(Duration.ofHours(24));
            sender.clear();

            handler.handle(start(NEWCOMER, null, Optional.of("inv_" + invite.code())));

            assertThat(members.findByTelegramUserId(NEWCOMER)).isEmpty();
            assertThat(sender.texts).containsExactly(Texts.INVITE_INVALID);
        }

        @Test
        void usedInviteCreatesNoMember() {
            Member mom = founder();
            Invite invite = inviteService.issue(mom, Role.CHILD);
            inviteService.redeem(invite.code(), 700000L, 700000L, "Первый");
            sender.clear();

            handler.handle(start(NEWCOMER, null, Optional.of("inv_" + invite.code())));

            assertThat(members.findByTelegramUserId(NEWCOMER)).isEmpty();
            assertThat(sender.texts).containsExactly(Texts.INVITE_INVALID);
        }

        /** Истёкший, использованный и несуществующий код снаружи неразличимы. */
        @Test
        void unknownInviteLooksExactlyLikeAnExpiredOne() {
            founder();
            sender.clear();

            handler.handle(start(NEWCOMER, null, Optional.of("inv_такогонет")));

            assertThat(sender.texts).containsExactly(Texts.INVITE_INVALID);
        }

        /**
         * Без этого исключение необратимо: строка остаётся, и человек навсегда «уже в семье».
         * Ребёнка, исключённого по ошибке, обратно не позовёшь.
         */
        @Test
        void removedMemberCanComeBackByANewInvite() {
            Member mom = founder();
            Member kid = inviteService.redeem(inviteService.issue(mom, Role.CHILD).code(), NEWCOMER, CHAT, "Петя");
            familyService.removeMember(mom, kid.id());
            assertThat(members.findByTelegramUserId(NEWCOMER).orElseThrow().status())
                    .isEqualTo(MemberStatus.REMOVED);
            Invite second = inviteService.issue(mom, Role.CHILD);
            sender.clear();

            handler.handle(start(NEWCOMER, null, Optional.of("inv_" + second.code())));

            Member returned = members.findByTelegramUserId(NEWCOMER).orElseThrow();
            assertThat(returned.status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(returned.id()).isEqualTo(kid.id());
            assertThat(sender.texts.getFirst()).contains(Texts.MAIN_MENU);
        }
    }

    @Nested
    class ExistingMember {

        @Test
        void seesTheMainMenu() {
            Member mom = founder();
            sender.clear();

            handler.handle(start(mom.telegramUserId(), mom, Optional.empty()));

            assertThat(sender.texts).containsExactly(Texts.MAIN_MENU);
        }

        /** Личный чат мог смениться — без свежего id напоминания недоставимы. */
        @Test
        void privateChatIdIsRefreshed() {
            Member mom = founder();
            BotRequest request =
                    new BotRequest(
                            mom.telegramUserId(),
                            777777L,
                            "Мама",
                            Optional.of(mom),
                            "/start",
                            Optional.of("start"),
                            Optional.empty(),
                            Optional.of(1),
                            Optional.empty());

            handler.handle(request);

            assertThat(members.findById(mom.familyId(), mom.id()).orElseThrow().privateChatId())
                    .isEqualTo(777777L);
        }

        @Test
        void invitationLinkDoesNotMoveThemToAnotherFamily() {
            Member mom = founder();
            sender.clear();

            handler.handle(start(mom.telegramUserId(), mom, Optional.of("inv_чужойкод")));

            assertThat(sender.texts.getFirst()).startsWith(Texts.ALREADY_IN_FAMILY);
            assertThat(members.findByTelegramUserId(mom.telegramUserId()).orElseThrow().familyId())
                    .isEqualTo(mom.familyId());
        }
    }

    // --- вспомогательное ---

    private Member founder() {
        return familyService.createFamily(100000001L, 100000001L, "Мама", "Ивановы", MOSCOW);
    }

    private void createFamily(String name, int timezoneIndex) {
        handler.handle(start(NEWCOMER, null, Optional.empty()));
        handler.continueDialog(text(NEWCOMER, name));
        handler.handle(callback(NEWCOMER), CallbackData.of("s", "tz", timezoneIndex));
    }

    private static int moscowIndex() {
        for (int i = 0; i < 20; i++) {
            if (TimezoneKeyboard.resolve(Integer.toString(i)).filter(MOSCOW::equals).isPresent()) {
                return i;
            }
        }
        throw new AssertionError("Москвы нет в клавиатуре");
    }

    private static BotRequest start(long userId, Member member, Optional<String> argument) {
        return new BotRequest(
                userId,
                CHAT,
                "Петя",
                Optional.ofNullable(member),
                "/start",
                Optional.of("start"),
                argument,
                Optional.of(1),
                Optional.empty());
    }

    private static BotRequest text(long userId, String text) {
        return new BotRequest(
                userId,
                CHAT,
                "Петя",
                Optional.empty(),
                text,
                Optional.empty(),
                Optional.empty(),
                Optional.of(2),
                Optional.empty());
    }

    private static BotRequest callback(long userId) {
        return new BotRequest(
                userId,
                CHAT,
                "Петя",
                Optional.empty(),
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(3),
                Optional.of("cb-1"));
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
        private final List<InlineKeyboardMarkup> markups = new ArrayList<>();

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

        void clear() {
            texts.clear();
            markups.clear();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
