package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.BotSettings;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.keyboard.TimezoneKeyboard;
import com.familytodo.adapter.telegram.view.FamilyView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.InviteService;
import com.familytodo.application.TaskService;
import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.FakeNotifier.Kind;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryInviteRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.InviteCodeGenerator;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.Clock;
import java.time.Instant;
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

class FamilyHandlerTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-08T16:00:00Z");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryInviteRepository invites = new InMemoryInviteRepository();
    private final InMemoryTaskRepository repository = new InMemoryTaskRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingSender sender = new RecordingSender();

    private FamilyService familyService;
    private InviteService inviteService;
    private TaskService taskService;
    private FamilyHandler handler;

    private Member mom;
    private Member dad;
    private Member kid;

    @BeforeEach
    void setUp() {
        familyService = new FamilyService(families, members, repository, notifier, clock);
        inviteService = new InviteService(invites, members, new InviteCodeGenerator(), clock);
        taskService = new TaskService(repository, members, notifier, clock);
        handler = new FamilyHandler(
                familyService,
                inviteService,
                sender,
                new com.familytodo.adapter.telegram.DialogStateStore(),
                BotSettings.of("1:test-token", "FamilyTODO_bot"));

        mom = familyService.createFamily(100000001L, 100000001L, "Мама", "Румянцевы", MOSCOW);
        dad = join(100001L, "Папа", Role.PARENT);
        kid = join(100002L, "Петя", Role.CHILD);
        sender.clear();
        notifier.clear();
    }

    @Nested
    class Roster {

        @Test
        void showsEveryoneWithTheirRole() {
            handler.handle(command(mom));

            assertThat(sender.texts.getFirst())
                    .contains("Румянцевы", "Мама", "родитель", "Петя", "ребёнок", "Europe/Moscow");
        }

        @Test
        void childSeesRosterButNoButtons() {
            handler.handle(command(kid));

            assertThat(sender.texts.getFirst()).contains("Румянцевы", "Петя");
            assertThat(sender.markups.getFirst().getKeyboard()).isEmpty();
        }

        @Test
        void removedMembersAreNotListed() {
            familyService.removeMember(mom, kid.id());
            sender.clear();

            handler.handle(command(mom));

            assertThat(sender.texts.getFirst()).doesNotContain("Петя");
        }
    }

    @Nested
    class Invitations {

        @Test
        void parentGetsAOneTimeLink() {
            handler.handle(callback(mom), action(FamilyView.INVITE, "child"));

            String text = sender.texts.getFirst();
            assertThat(text).contains("https://t.me/FamilyTODO_bot?start=inv_");
            String code = text.substring(text.indexOf("inv_") + 4).trim();
            assertThat(invites.findByCode(code)).isPresent();
            assertThat(invites.findByCode(code).orElseThrow().role()).isEqualTo(Role.CHILD);
        }

        @Test
        void roleIsFixedAtIssueTime() {
            handler.handle(callback(mom), action(FamilyView.INVITE, "parent"));

            String text = sender.texts.getFirst();
            String code = text.substring(text.indexOf("inv_") + 4).trim();
            assertThat(invites.findByCode(code).orElseThrow().role()).isEqualTo(Role.PARENT);
        }

        /** Кнопки ребёнку не показываются, но нажатие может прийти подделанной строкой. */
        @Test
        void childCannotInviteEvenWithAForgedPress() {
            handler.handle(callback(kid), action(FamilyView.INVITE, "parent"));

            assertThat(sender.texts).containsExactly(Texts.INVITE_IS_FOR_PARENTS);
            assertThat(invites.findByCode("любой")).isEmpty();
        }

        /** Ссылка действительно работает: код проходит через приём приглашения. */
        @Test
        void theIssuedLinkActuallyLetsSomeoneIn() {
            handler.handle(callback(mom), action(FamilyView.INVITE, "child"));
            String text = sender.texts.getFirst();
            String code = text.substring(text.indexOf("inv_") + 4).trim();

            Member joined = inviteService.redeem(code, 700000L, 700000L, "Новенький");

            assertThat(joined.familyId()).isEqualTo(mom.familyId());
            assertThat(joined.role()).isEqualTo(Role.CHILD);
        }
    }

    @Nested
    class Removal {

        @Test
        void parentRemovesAChild() {
            handler.handle(callback(mom), action(FamilyView.REMOVE_DO, Long.toString(kid.id())));

            assertThat(members.findById(mom.familyId(), kid.id()).orElseThrow().status())
                    .isEqualTo(MemberStatus.REMOVED);
        }

        @Test
        void removalCancelsOpenTasksAndTellsTheirAuthors() {
            Task task = taskService.create(mom, kid.id(), "Вынести мусор", DUE);
            notifier.clear();

            handler.handle(callback(mom), action(FamilyView.REMOVE_DO, Long.toString(kid.id())));

            assertThat(repository.findById(mom.familyId(), task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.DECLINED);
            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(Kind.CANCELLED, mom.id()));
        }

        @Test
        void childCannotRemoveAnyone() {
            handler.handle(callback(kid), action(FamilyView.REMOVE_DO, Long.toString(dad.id())));

            assertThat(sender.texts).containsExactly(Texts.REMOVE_IS_FOR_PARENTS);
            assertThat(members.findById(mom.familyId(), dad.id()).orElseThrow().status())
                    .isEqualTo(MemberStatus.ACTIVE);
        }

        /** Отказ должен объяснять причину, иначе выглядит как поломка. */
        @Test
        void lastParentIsRefusedWithAnExplanation() {
            familyService.removeMember(mom, dad.id());
            sender.clear();

            handler.handle(callback(mom), action(FamilyView.REMOVE_DO, Long.toString(mom.id())));

            assertThat(sender.edits).containsExactly(Texts.LAST_PARENT_STAYS);
            assertThat(members.findById(mom.familyId(), mom.id()).orElseThrow().status())
                    .isEqualTo(MemberStatus.ACTIVE);
        }

        @Test
        void oneOfTwoParentsMayStillBeRemoved() {
            handler.handle(callback(mom), action(FamilyView.REMOVE_DO, Long.toString(dad.id())));

            assertThat(members.findById(mom.familyId(), dad.id()).orElseThrow().status())
                    .isEqualTo(MemberStatus.REMOVED);
        }

        @Test
        void confirmationIsAskedBeforeRemoving() {
            handler.handle(callback(mom), action(FamilyView.REMOVE_ASK, Long.toString(kid.id())));

            assertThat(sender.edits.getFirst()).contains("Исключить Петя");
            assertThat(members.findById(mom.familyId(), kid.id()).orElseThrow().status())
                    .isEqualTo(MemberStatus.ACTIVE);
        }

        @Test
        void memberOfAnotherFamilyIsNotFound() {
            Member outsider = familyService.createFamily(900001L, 900001L, "Чужой", "Петровы", MOSCOW);
            sender.clear();

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(mom),
                                            action(
                                                    FamilyView.REMOVE_DO,
                                                    Long.toString(outsider.id()))))
                    .isInstanceOf(com.familytodo.domain.DomainException.NotFound.class);
        }
    }

    @Nested
    class Settings {

        @Test
        void timezoneChangeIsStored() {
            int index = indexOf(ZoneId.of("Asia/Yekaterinburg"));

            handler.handle(callback(mom), action(FamilyView.TIMEZONE, Integer.toString(index)));

            assertThat(families.findById(mom.familyId()).orElseThrow().timezone())
                    .isEqualTo(ZoneId.of("Asia/Yekaterinburg"));
        }

        @Test
        void digestTimeChangeIsStored() {
            handler.handle(callback(mom), action(FamilyView.DIGEST, "7"));

            assertThat(families.findById(mom.familyId()).orElseThrow().digestTime())
                    .isEqualTo(LocalTime.of(7, 0));
        }

        @Test
        void digestHorizonChangeIsStored() {
            handler.handle(callback(mom), action(FamilyView.HORIZON, "7"));

            assertThat(families.findById(mom.familyId()).orElseThrow().digestHorizonDays())
                    .isEqualTo(7);
        }

        @Test
        void settingsMenuOffersTheHorizon() {
            handler.handle(callback(mom), action(FamilyView.SETTINGS, "0"));

            assertThat(buttonLabels()).anyMatch(label -> label.contains("Горизонт"));
        }

        @Test
        void horizonMenuOffersEveryAllowedValue() {
            handler.handle(callback(mom), action(FamilyView.HORIZON, "ask"));

            assertThat(sender.markups.getLast().getKeyboard().getFirst())
                    .hasSize(Family.DIGEST_HORIZONS.size());
        }

        @Test
        void childCannotChangeTheHorizon() {
            handler.handle(callback(kid), action(FamilyView.HORIZON, "7"));

            assertThat(sender.texts).containsExactly(Texts.SETTINGS_ARE_FOR_PARENTS);
            assertThat(families.findById(mom.familyId()).orElseThrow().digestHorizonDays())
                    .isEqualTo(1);
        }

        /** Значение приходит из callback_data — то есть от клиента, и доверять ему нельзя. */
        @Test
        void forgedHorizonIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), action(FamilyView.HORIZON, "365")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(families.findById(mom.familyId()).orElseThrow().digestHorizonDays())
                    .isEqualTo(1);
        }

        @Test
        void nonNumericHorizonIsRejected() {
            assertThatThrownBy(() -> handler.handle(callback(mom), action(FamilyView.HORIZON, "неделя")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void childCannotChangeSettings() {
            handler.handle(callback(kid), action(FamilyView.DIGEST, "7"));

            assertThat(sender.texts).containsExactly(Texts.SETTINGS_ARE_FOR_PARENTS);
            assertThat(families.findById(mom.familyId()).orElseThrow().digestTime())
                    .isEqualTo(LocalTime.of(8, 0));
        }

        /** Индекс приходит от клиента: за пределами списка ничего меняться не должно. */
        @Test
        void forgedTimezoneIndexChangesNothing() {
            handler.handle(callback(mom), action(FamilyView.TIMEZONE, "999"));

            assertThat(families.findById(mom.familyId()).orElseThrow().timezone())
                    .isEqualTo(MOSCOW);
        }

        @Test
        void forgedDigestHourIsRejected() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> handler.handle(callback(mom), action(FamilyView.DIGEST, "3")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(families.findById(mom.familyId()).orElseThrow().digestTime())
                    .isEqualTo(LocalTime.of(8, 0));
        }

        private int indexOf(ZoneId zone) {
            for (int i = 0; i < 20; i++) {
                if (TimezoneKeyboard.resolve(Integer.toString(i)).filter(zone::equals).isPresent()) {
                    return i;
                }
            }
            throw new AssertionError("зоны нет в клавиатуре");
        }
    }

    // --- вспомогательное ---

    private List<String> buttonLabels() {
        return sender.markups.getLast().getKeyboard().stream()
                .flatMap(row -> row.stream())
                .map(button -> button.getText())
                .toList();
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

    private static CallbackData action(String action, String argument) {
        return new CallbackData(FamilyView.PREFIX, action, argument);
    }

    private static BotRequest command(Member member) {
        return build(member, "/family", Optional.of("family"), Optional.empty());
    }

    private static BotRequest callback(Member member) {
        return build(member, "", Optional.empty(), Optional.of("cb-1"));
    }

    private static BotRequest build(
            Member member, String text, Optional<String> command, Optional<String> callbackId) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                text,
                command,
                Optional.empty(),
                Optional.of(7),
                callbackId);
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
        private final List<String> edits = new ArrayList<>();
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

        @Override
        public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
            edits.add(html);
            markups.add(markup);
        }

        void clear() {
            texts.clear();
            edits.clear();
            markups.clear();
        }
    }
}
