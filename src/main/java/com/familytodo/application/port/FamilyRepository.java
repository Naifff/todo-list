package com.familytodo.application.port;

import com.familytodo.domain.Family;
import java.util.List;
import java.util.Optional;

public interface FamilyRepository {

    long nextId();

    Family save(Family family);

    Optional<Family> findById(long familyId);

    /** Нужен джобе дайджеста: она обходит все семьи и сверяет их локальное время. */
    List<Family> findAll();
}
