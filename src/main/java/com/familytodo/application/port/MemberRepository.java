package com.familytodo.application.port;

import com.familytodo.domain.Member;
import java.util.List;
import java.util.Optional;

public interface MemberRepository {

    long nextId();

    Member save(Member member);

    Optional<Member> findById(long familyId, long memberId);

    /**
     * Единственная выборка без {@code familyId} — и она же точка входа в приложение: по апдейту
     * Telegram известен только id пользователя. Семья определяется её результатом, а дальше уже
     * все запросы ограничены найденным {@code familyId}. Работает потому, что {@code
     * telegram_user_id} уникален глобально: один человек = одна семья.
     */
    Optional<Member> findByTelegramUserId(long telegramUserId);

    List<Member> findActive(long familyId);
}
