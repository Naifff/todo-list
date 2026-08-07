package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.familytodo.application.FamilyService;
import com.familytodo.application.InviteService;
import com.familytodo.application.port.InviteRepository;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.Invite;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Вход по приглашению атомарен: участник и погашение кода коммитятся вместе.
 *
 * <p>Без транзакции сбой между двумя записями оставлял бы человека в семье с живой одноразовой
 * ссылкой — по ней вошёл бы кто-то ещё. До правки порядка окно было с обратным исходом: код сгорал,
 * а человек не входил. Обе беды лечит одна транзакция.
 *
 * <p>⚠️ Этот тест поднимает <b>настоящий контекст</b>, и иначе нельзя: {@code @Transactional}
 * работает только через прокси Spring. Остальные интеграционные тесты собирают сервисы руками, и в
 * них аннотация — пустая декорация, которая ничего не откатывает и никого об этом не предупреждает.
 */
@SpringBootTest(
        properties = {
            "telegram.bot.token=1:tx-test",
            "telegram.bot.username=tx_test_bot",
            "telegram.bot.polling.enabled=false",
            "spring.datasource.url=jdbc:sqlite::memory:?foreign_keys=true"
        })
class InviteTransactionIT {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    /**
     * Идентификаторы уникальны на каждый тест.
     *
     * <p>Контекст {@code @SpringBootTest} переиспользуется, база в памяти живёт всё время класса, а
     * {@code telegram_user_id} глобально уникален. Общий идентификатор означал бы, что второй тест
     * попадает на путь возврата исключённого и падает не тем, чем должен.
     *
     * <p>Счётчик <b>статический</b>: JUnit создаёт новый экземпляр теста на каждый метод, и поле
     * объекта начинало бы отсчёт заново.
     */
    private static final AtomicLong telegramIds = new AtomicLong(700_000L);

    @Autowired private InviteService invites;
    @Autowired private FamilyService families;
    @Autowired private MemberRepository members;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoSpyBean private InviteRepository inviteRepository;

    /** Без менеджера транзакций аннотация не значит ничего — проверяем, что он в контексте есть. */
    @Test
    void contextHasATransactionManager() {
        assertThat(transactionManager).isNotNull();
    }

    @Test
    void successfulRedemptionCommitsBothRows() {
        Member founder = founder();
        Invite invite = invites.issue(founder, Role.PARENT);
        long joiner = telegramIds.incrementAndGet();

        Member joined = invites.redeem(invite.code(), joiner, joiner, "Жена");

        assertThat(members.findByTelegramUserId(joiner)).isPresent();
        assertThat(inviteRepository.findByCode(invite.code()).orElseThrow().usedBy())
                .isEqualTo(joined.id());
    }

    /**
     * Главная проверка: падение на погашении кода откатывает и создание участника.
     *
     * <p>Сбой вносится в сохранение <b>погашенного</b> приглашения — выпуск при этом работает
     * обычным образом, иначе тест не дошёл бы до нужного места.
     */
    @Test
    void failureWhileBurningTheInviteRollsBackTheMember() {
        Member founder = founder();
        Invite invite = invites.issue(founder, Role.PARENT);
        long joiner = telegramIds.incrementAndGet();

        doThrow(new DataIntegrityViolationException("не удалось погасить код"))
                .when(inviteRepository)
                .save(argThat(candidate -> candidate != null && candidate.usedBy() != null));

        assertThatThrownBy(() -> invites.redeem(invite.code(), joiner, joiner, "Жена"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(members.findByTelegramUserId(joiner))
                .describedAs("участник не должен остаться в семье с живым кодом")
                .isEmpty();
    }

    private Member founder() {
        long telegramId = telegramIds.incrementAndGet();
        return families.createFamily(telegramId, telegramId, "Мама", "Румянцевы", MOSCOW);
    }
}
