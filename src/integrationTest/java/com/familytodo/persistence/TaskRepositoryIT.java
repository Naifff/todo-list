package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcTaskRepository;
import com.familytodo.application.TaskQuery;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskRepositoryIT extends AbstractSqliteIT {

    private static final long FAMILY_A = 1L;
    private static final long FAMILY_B = 2L;

    private static final long MOM = 10L;
    private static final long KID = 12L;
    private static final long OUTSIDER = 90L;

    private static final Instant CREATED = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-07T16:00:00Z");
    private static final Set<TaskStatus> OPEN = EnumSet.of(TaskStatus.OPEN);

    private JdbcTaskRepository repository;

    @BeforeEach
    void seed() {
        repository = new JdbcTaskRepository(jdbc, new JdbcIdSequence(jdbc));

        insertFamily(FAMILY_A, "Ивановы");
        insertFamily(FAMILY_B, "Петровы");
        insertMember(MOM, FAMILY_A, "Мама", Role.PARENT);
        insertMember(KID, FAMILY_A, "Петя", Role.CHILD);
        insertMember(OUTSIDER, FAMILY_B, "Чужой", Role.PARENT);
    }

    @Test
    void roundTripsEveryField() {
        Task task = repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));

        Task loaded = repository.findById(FAMILY_A, task.id()).orElseThrow();

        assertThat(loaded.title()).isEqualTo("Вынести мусор");
        assertThat(loaded.familyId()).isEqualTo(FAMILY_A);
        assertThat(loaded.creatorId()).isEqualTo(MOM);
        assertThat(loaded.assignee()).isEqualTo(new Assignee(KID, Role.CHILD));
        assertThat(loaded.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(loaded.dueAt()).isEqualTo(DUE);
        assertThat(loaded.createdAt()).isEqualTo(CREATED);
        assertThat(loaded.closedAt()).isNull();
        assertThat(loaded.declineReason()).isNull();
    }

    /** Доли секунды — ровно тот случай, на котором сломалось бы текстовое хранение времени. */
    @Test
    void keepsSubSecondPrecision() {
        Instant precise = DUE.plusMillis(123);
        repository.save(
                Task.create(
                        100L,
                        FAMILY_A,
                        "Вынести мусор",
                        MOM,
                        new Assignee(KID, Role.CHILD),
                        precise,
                        CREATED));

        assertThat(repository.findById(FAMILY_A, 100L).orElseThrow().dueAt()).isEqualTo(precise);
    }

    @Test
    void savingTwiceUpdatesInsteadOfDuplicating() {
        Task task = repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        task.complete(com.familytodo.domain.Actor.member(KID, FAMILY_A, Role.CHILD), DUE);
        repository.save(task);

        Task loaded = repository.findById(FAMILY_A, 100L).orElseThrow();
        assertThat(loaded.status()).isEqualTo(TaskStatus.DONE);
        assertThat(loaded.closedAt()).isEqualTo(DUE);
        assertThat(jdbc.sql("select count(*) from task").query(Long.class).single()).isEqualTo(1L);
    }

    /** Роль исполнителя живёт в member: после повышения задача должна читаться с новой ролью. */
    @Test
    void readsAssigneeRoleFromMemberTable() {
        repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        jdbc.sql("update member set role = 'PARENT' where id = ?").param(KID).update();

        assertThat(repository.findById(FAMILY_A, 100L).orElseThrow().assignee().role())
                .isEqualTo(Role.PARENT);
    }

    @Test
    void taskOfAnotherFamilyIsNotVisibleById() {
        repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));

        assertThat(repository.findById(FAMILY_B, 100L)).isEmpty();
    }

    @Test
    void listsNeverCrossFamilyBoundary() {
        repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));

        List<Task> foreign =
                repository.find(new TaskQuery(FAMILY_B, null, null, null, OPEN));

        assertThat(foreign).isEmpty();
    }

    @Test
    void deleteIsScopedToFamily() {
        repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));

        repository.delete(FAMILY_B, 100L);

        assertThat(repository.findById(FAMILY_A, 100L)).isPresent();
    }

    /** Фильтр ребёнка выполняется в SQL — проверяем на выдаче, а не на построении запроса. */
    @Test
    void childOnlySeesOwnTasksInSql() {
        repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        repository.save(open(101L, FAMILY_A, MOM, MOM, Role.PARENT));
        repository.save(open(102L, FAMILY_A, KID, MOM, Role.PARENT));

        List<Task> visible = repository.find(new TaskQuery(FAMILY_A, KID, null, null, OPEN));

        assertThat(visible).extracting(Task::id).containsExactly(100L, 102L);
    }

    @Test
    void parentSeesEveryTaskOfTheFamily() {
        repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        repository.save(open(101L, FAMILY_A, MOM, MOM, Role.PARENT));

        List<Task> visible = repository.find(new TaskQuery(FAMILY_A, null, null, null, OPEN));

        assertThat(visible).hasSize(2);
    }

    @Test
    void filtersByAssigneeAndByCreator() {
        repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        repository.save(open(101L, FAMILY_A, KID, MOM, Role.PARENT));

        assertThat(repository.find(new TaskQuery(FAMILY_A, null, KID, null, OPEN)))
                .extracting(Task::id)
                .containsExactly(100L);
        assertThat(repository.find(new TaskQuery(FAMILY_A, null, null, KID, OPEN)))
                .extracting(Task::id)
                .containsExactly(101L);
    }

    @Test
    void closedTasksAreExcludedFromOpenLists() {
        Task task = repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        task.complete(com.familytodo.domain.Actor.member(KID, FAMILY_A, Role.CHILD), DUE);
        repository.save(task);

        assertThat(repository.find(new TaskQuery(FAMILY_A, null, null, null, OPEN))).isEmpty();
    }

    /** Бессрочные — в конце списка: срок это то, что торопит, а его отсутствие не торопит. */
    @Test
    void ordersByDueDateWithUndatedLast() {
        repository.save(
                Task.create(100L, FAMILY_A, "Позже", MOM, new Assignee(KID, Role.CHILD), DUE.plusSeconds(3600), CREATED));
        repository.save(
                Task.create(101L, FAMILY_A, "Без срока", MOM, new Assignee(KID, Role.CHILD), null, CREATED));
        repository.save(
                Task.create(102L, FAMILY_A, "Раньше", MOM, new Assignee(KID, Role.CHILD), DUE, CREATED));

        assertThat(repository.find(new TaskQuery(FAMILY_A, null, null, null, OPEN)))
                .extracting(Task::title)
                .containsExactly("Раньше", "Позже", "Без срока");
    }

    @Test
    void doesNotHandOutTheSameIdTwice() {
        assertThat(repository.nextId()).isNotEqualTo(repository.nextId());
    }

    private static Task open(long id, long familyId, long creator, long assignee, Role role) {
        return Task.create(
                id, familyId, "Вынести мусор", creator, new Assignee(assignee, role), DUE, CREATED);
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
