package com.familytodo.application.fake;

import com.familytodo.application.port.FamilyRepository;
import com.familytodo.domain.Family;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryFamilyRepository implements FamilyRepository {

    private final Map<Long, Family> families = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public Family save(Family family) {
        families.put(family.id(), family);
        return family;
    }

    @Override
    public Optional<Family> findById(long familyId) {
        return Optional.ofNullable(families.get(familyId));
    }

    @Override
    public List<Family> findAll() {
        return List.copyOf(families.values());
    }
}
