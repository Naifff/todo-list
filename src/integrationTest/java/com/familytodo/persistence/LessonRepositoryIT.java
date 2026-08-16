package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcLessonRepository;
import com.familytodo.domain.Lesson;
import com.familytodo.domain.Role;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Расписание на настоящем SQLite.
 *
 * <p>⚠️ Фейк здесь не годится принципиально: он не проверяет внешние ключи и не знает про {@code
 * id_sequence}. Ровно так баг приглашений дожил до прода, а строку в {@code id_sequence} для новой
 * таблицы в этом проекте уже забывали — и проявилось это не при старте, а при первой живой попытке
 * что-нибудь создать.
 */
class LessonRepositoryIT extends AbstractSqliteIT {

    private static final long FAMILY_A = 1L;
    private static final long FAMILY_B = 2L;

    private static final long MOM = 10L;
    private static final long KID = 12L;
    private static final long OTHER_KID = 13L;
    private static final long OUTSIDER = 90L;

    private static final Instant CREATED = Instant.parse("2026-08-15T09:00:00Z");
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);

    private JdbcLessonRepository repository;

    @BeforeEach
    void seed() {
        repository = new JdbcLessonRepository(jdbc, new JdbcIdSequence(jdbc));

        insertFamily(FAMILY_A, "Ивановы");
        insertFamily(FAMILY_B, "Петровы");
        insertMember(MOM, FAMILY_A, "Мама", Role.PARENT);
        insertMember(KID, FAMILY_A, "Петя", Role.CHILD);
        insertMember(OTHER_KID, FAMILY_A, "Вася", Role.CHILD);
        insertMember(OUTSIDER, FAMILY_B, "Чужой", Role.CHILD);
    }

    /** Ловит забытую строку в {@code id_sequence} — её в этом проекте уже забывали на {@code V4}. */
    @Test
    void theSequenceHandsOutIdsForTheNewTable() {
        assertThat(repository.nextId()).isPositive();
        assertThat(repository.nextId()).isGreaterThan(1L);
    }

    @Test
    void roundTripsEveryField() {
        repository.replace(FAMILY_A, KID, List.of(lesson(1L, KID, DayOfWeek.TUESDAY, "Математика")));

        Lesson loaded = repository.findByMember(FAMILY_A, KID).getFirst();

        assertThat(loaded.memberId()).isEqualTo(KID);
        assertThat(loaded.day()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(loaded.startsAt()).isEqualTo(LocalTime.of(8, 30));
        assertThat(loaded.endsAt()).isEqualTo(LocalTime.of(9, 15));
        assertThat(loaded.subject()).isEqualTo("Математика");
        assertThat(loaded.validFrom()).isEqualTo(SEPTEMBER);
        assertThat(loaded.validTo()).isEqualTo(LocalDate.of(2027, 5, 31));
        assertThat(loaded.createdAt()).isEqualTo(CREATED);
    }

    /** Замена — одной операцией: между стиранием и вставкой ребёнок не остаётся без расписания. */
    @Test
    void replaceWipesOnlyThatPupilsSchedule() {
        repository.replace(FAMILY_A, KID, List.of(lesson(1L, KID, DayOfWeek.MONDAY, "Математика")));
        repository.replace(
                FAMILY_A, OTHER_KID, List.of(lesson(2L, OTHER_KID, DayOfWeek.MONDAY, "Русский")));

        int before = repository.replace(FAMILY_A, KID, List.of(lesson(3L, KID, DayOfWeek.FRIDAY, "Физра")));

        assertThat(before).isEqualTo(1);
        assertThat(repository.findByMember(FAMILY_A, KID))
                .extracting(Lesson::subject)
                .containsExactly("Физра");
        assertThat(repository.findByMember(FAMILY_A, OTHER_KID))
                .extracting(Lesson::subject)
                .containsExactly("Русский");
    }

    /** Ключевое свойство всего проекта: чужая семья не видна ни при каких обстоятельствах. */
    @Test
    void anotherFamilyNeverAppears() {
        repository.replace(FAMILY_A, KID, List.of(lesson(1L, KID, DayOfWeek.MONDAY, "Математика")));

        assertThat(repository.findByMember(FAMILY_B, KID)).isEmpty();
    }

    /**
     * Уроки достаются только по участнику: выборки «всё, что мне видно» у расписания нет вовсе,
     * и брат в ответ не попадает никогда — ни родителю, ни ребёнку.
     */
    @Test
    void lessonsOfAnotherChildNeverComeBack() {
        repository.replace(FAMILY_A, KID, List.of(lesson(1L, KID, DayOfWeek.MONDAY, "Математика")));
        repository.replace(
                FAMILY_A, OTHER_KID, List.of(lesson(2L, OTHER_KID, DayOfWeek.MONDAY, "Русский")));

        assertThat(repository.findByMember(FAMILY_A, KID))
                .extracting(Lesson::subject)
                .containsExactly("Математика");
    }

    /**
     * ⚠️ Внешние ключи в SQLite выключены по умолчанию — без прагмы в URL строка сослалась бы на
     * несуществующего участника, и расписание чужого ребёнка легло бы в нашу семью.
     */
    @Test
    void aLessonForAnUnknownMemberIsRefusedByTheSchema() {
        assertThatThrownBy(
                        () ->
                                repository.replace(
                                        FAMILY_A, 999L, List.of(lesson(1L, 999L, DayOfWeek.MONDAY, "Математика"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Lesson lesson(long id, long memberId, DayOfWeek day, String subject) {
        return Lesson.create(
                id,
                FAMILY_A,
                memberId,
                day,
                LocalTime.of(8, 30),
                LocalTime.of(9, 15),
                subject,
                SEPTEMBER,
                LocalDate.of(2027, 5, 31),
                CREATED);
    }

    private void insertFamily(long id, String name) {
        jdbc.sql(
                        """
                        insert into family (id, name, timezone, digest_time, last_digest_date, created_at)
                        values (?, ?, 'Europe/Moscow', '08:00', '2026-08-07', 0)
                        """)
                .params(id, name)
                .update();
    }

    private void insertMember(long id, long familyId, String name, Role role) {
        jdbc.sql(
                        """
                        insert into member
                          (id, family_id, telegram_user_id, private_chat_id,
                           display_name, role, status, created_at)
                        values (?, ?, ?, ?, ?, ?, 'ACTIVE', 0)
                        """)
                .params(id, familyId, id + 1000L, id + 1000L, name, role.name())
                .update();
    }
}
