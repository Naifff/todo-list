package com.familytodo.application.fake;

import com.familytodo.application.port.InviteRepository;
import com.familytodo.domain.Invite;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryInviteRepository implements InviteRepository {

    private final Map<String, Invite> byCode = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public Invite save(Invite invite) {
        byCode.put(invite.code(), invite);
        return invite;
    }

    @Override
    public Optional<Invite> findByCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }
}
