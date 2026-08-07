package com.familytodo.application.fake;

import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.Member;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryMemberRepository implements MemberRepository {

    private final Map<Long, Member> members = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public Member save(Member member) {
        members.put(member.id(), member);
        return member;
    }

    @Override
    public Optional<Member> findById(long familyId, long memberId) {
        return Optional.ofNullable(members.get(memberId)).filter(m -> m.familyId() == familyId);
    }

    @Override
    public Optional<Member> findByTelegramUserId(long telegramUserId) {
        return members.values().stream()
                .filter(m -> m.telegramUserId() == telegramUserId)
                .findFirst();
    }

    @Override
    public List<Member> findActive(long familyId) {
        List<Member> active = new ArrayList<>();
        for (Member member : members.values()) {
            if (member.familyId() == familyId && member.isActive()) {
                active.add(member);
            }
        }
        return active;
    }
}
