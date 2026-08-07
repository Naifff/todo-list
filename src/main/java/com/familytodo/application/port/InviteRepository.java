package com.familytodo.application.port;

import com.familytodo.domain.Invite;
import java.util.Optional;

public interface InviteRepository {

    long nextId();

    Invite save(Invite invite);

    /**
     * Поиск по коду идёт без {@code familyId} — семья как раз и определяется найденным
     * приглашением. Код одноразовый и живёт 24 часа, а перебор бессмыслен: 128 бит энтропии.
     */
    Optional<Invite> findByCode(String code);
}
