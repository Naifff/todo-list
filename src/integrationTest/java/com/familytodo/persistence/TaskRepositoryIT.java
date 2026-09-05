package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcTaskRepository;
import com.familytodo.application.TaskQuery;
import com.familytodo.domain.Actor;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Assignment;
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
        assertThat(loaded.assignments())
                .containsExactly(new Assignment(KID, Role.CHILD, null, null));
        assertThat(loaded.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(loaded.dueAt()).isEqualTo(DUE);
        assertThat(loaded.createdAt()).isEqualTo(CREATED);
        assertThat(loaded.closedAt()).isNull();
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

        assertThat(repository.findById(FAMILY_A, 100L).orElseThrow().assignments().getFirst().role())
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

    /** Интервал и место должны переживать запись и чтение — их добавила V2. */
    @Test
    void roundTripsScheduleAndLocation() {
        Task task = repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        task.schedule(
                com.familytodo.domain.Actor.member(MOM, FAMILY_A, Role.PARENT),
                Instant.parse("2026-09-01T05:00:00Z"),
                Instant.parse("2026-09-01T05:40:00Z"),
                "школа");
        repository.save(task);

        Task loaded = repository.findById(FAMILY_A, 100L).orElseThrow();

        assertThat(loaded.startsAt()).isEqualTo(Instant.parse("2026-09-01T05:00:00Z"));
        assertThat(loaded.endsAt()).isEqualTo(Instant.parse("2026-09-01T05:40:00Z"));
        assertThat(loaded.location()).isEqualTo("школа");
        assertThat(loaded.isScheduled()).isTrue();
    }

    @Test
    void clearedScheduleIsStoredAsNull() {
        Task task = repository.save(open(100L, FAMILY_A, MOM, KID, Role.CHILD));
        var actor = com.familytodo.domain.Actor.member(MOM, FAMILY_A, Role.PARENT);
        task.schedule(actor, Instant.parse("2026-09-01T05:00:00Z"), null, "школа");
        repository.save(task);

        task.schedule(actor, null, null, null);
        repository.save(task);

        Task loaded = repository.findById(FAMILY_A, 100L).orElseThrow();
        assertThat(loaded.startsAt()).isNull();
        assertThat(loaded.location()).isNull();
    }

    /** Горизонт считается в SQL: дело попадает в окно по началу интервала, иначе по сроку. */
    @Test
    void filtersByRangeInSql() {
        repository.save(dated(100L, "Сегодня", "2026-08-07T16:00:00Z"));
        repository.save(dated(101L, "Через неделю", "2026-08-14T16:00:00Z"));

        List<Task> window =
                repository.find(
                        new TaskQuery(
                                FAMILY_A,
                                null,
                                null,
                                null,
                                OPEN,
                                Instant.parse("2026-08-07T00:00:00Z"),
                                Instant.parse("2026-08-08T00:00:00Z"),
                                false));

        assertThat(window).extracting(Task::title).containsExactly("Сегодня");
    }

    @Test
    void scheduledTaskEntersTheWindowByItsStartNotItsDeadline() {
        Task task = repository.save(dated(100L, "Отвезти детей", "2026-08-20T16:00:00Z"));
        task.schedule(
                com.familytodo.domain.Actor.member(MOM, FAMILY_A, Role.PARENT),
                Instant.parse("2026-08-07T05:00:00Z"),
                null,
                null);
        repository.save(task);

        List<Task> window =
                repository.find(
                        new TaskQuery(
                                FAMILY_A,
                                null,
                                null,
                                null,
                                OPEN,
                                Instant.parse("2026-08-07T00:00:00Z"),
                                Instant.parse("2026-08-08T00:00:00Z"),
                                false));

        assertThat(window).extracting(Task::title).containsExactly("Отвезти детей");
    }

    @Test
    void undatedQueryReturnsOnlyTasksWithNeitherDeadlineNorInterval() {
        repository.save(dated(100L, "Со сроком", "2026-08-07T16:00:00Z"));
        repository.save(
                Task.create(
                        101L, FAMILY_A, "Без срока", MOM, new Assignee(KID, Role.CHILD), null, CREATED));

        List<Task> undated =
                repository.find(new TaskQuery(FAMILY_A, null, null, null, OPEN, null, null, true));

        assertThat(undated).extracting(Task::title).containsExactly("Без срока");
    }

    /**
     * Прошедшее событие уходит в конец выборки, но остаётся в ней: иначе его нельзя ни открыть, ни
     * перенести. Проверяется на реальном SQL — порядок живёт в запросе.
     */
    /**
     * ⚠️ Список — строго хронология, без «корзин».
     *
     * <p>Прошедшее событие уводили в конец списка (15 августа), чтобы вчерашнее не выглядело
     * ближайшим делом. С тридцатью делами это стало читаться как сбитая сортировка: пролистав
     * сентябрь, человек упирался в август, а на третьей странице список начинался заново со
     * старого. Отменено 5 сентября: место в списке снова означает время, а «уже прошло» говорит
     * пометка ⌛ в самой строке — её тогда не было вовсе.
     */
    @Test
    void theOrderIsPlainlyChronologicalEvenForAFinishedEvent() {
        Task event = repository.save(dated(100L, "Ролики", "2026-08-20T16:00:00Z"));
        event.schedule(
                com.familytodo.domain.Actor.member(MOM, FAMILY_A, Role.PARENT),
                Instant.parse("2026-08-06T15:30:00Z"),
                Instant.parse("2026-08-06T17:00:00Z"),
                "цирк");
        repository.save(event);
        repository.save(dated(101L, "Вынести мусор", "2026-08-06T16:00:00Z"));

        List<Task> open =
                repository.find(
                        new TaskQuery(FAMILY_A, null, null, null, OPEN)
                                .withoutStale(Instant.parse("2026-08-01T00:00:00Z")));

        assertThat(open).extracting(Task::title).containsExactly("Ролики", "Вынести мусор");
    }

    /**
     * Протухшее уходит из выборки совсем — и <b>событие, и дело со сроком</b>.
     *
     * <p>⚠️ Прежде условие касалось только событий: считалось, что просроченный срок всё ещё можно
     * сделать, и он оставался навсегда. На проде это дало семнадцать строк середины августа,
     * которые никто уже не закроет: «прививка ВПЧ 19.08», «Гастроинтеролог 27.08». Отменено
     * 5 сентября — правило одно на всё, что имеет момент.
     */
    @Test
    void anythingOlderThanTheGraceIsGone() {
        Task event = repository.save(dated(100L, "Ролики", "2026-08-20T16:00:00Z"));
        event.schedule(
                com.familytodo.domain.Actor.member(MOM, FAMILY_A, Role.PARENT),
                Instant.parse("2026-08-01T15:30:00Z"),
                Instant.parse("2026-08-01T17:00:00Z"),
                "цирк");
        repository.save(event);
        repository.save(dated(101L, "Постирать рюкзаки", "2026-07-01T16:00:00Z"));
        repository.save(dated(102L, "Забрать паспорт", "2026-08-06T16:00:00Z"));

        List<Task> open =
                repository.find(
                        new TaskQuery(FAMILY_A, null, null, null, OPEN)
                                .withoutStale(Instant.parse("2026-08-04T00:00:00Z")));

        assertThat(open).extracting(Task::title).containsExactly("Забрать паспорт");
    }

    /** ⚠️ Дело без даты не протухает никогда: сравнивать его не с чем. */
    @Test
    void anUndatedTaskIsNeverStale() {
        repository.save(
                Task.create(
                        103L,
                        FAMILY_A,
                        "Разобрать шкаф",
                        MOM,
                        new com.familytodo.domain.Assignee(MOM, Role.PARENT),
                        null,
                        Instant.parse("2026-07-01T10:00:00Z")));

        List<Task> open =
                repository.find(
                        new TaskQuery(FAMILY_A, null, null, null, OPEN)
                                .withoutStale(Instant.parse("2026-08-04T00:00:00Z")));

        assertThat(open).extracting(Task::title).containsExactly("Разобрать шкаф");
    }

    /** Граница по дню: событие, кончившееся сегодня утром, из списка не уходит. */
    @Test
    void anEventThatEndedEarlierTodayStays() {
        Task event = repository.save(dated(100L, "Зарядка", "2026-08-20T16:00:00Z"));
        event.schedule(
                com.familytodo.domain.Actor.member(MOM, FAMILY_A, Role.PARENT),
                Instant.parse("2026-08-07T04:00:00Z"),
                Instant.parse("2026-08-07T04:30:00Z"),
                null);
        repository.save(event);

        List<Task> open =
                repository.find(
                        new TaskQuery(FAMILY_A, null, null, null, OPEN)
                                .withoutStale(Instant.parse("2026-07-31T21:00:00Z")));

        assertThat(open).extracting(Task::title).containsExactly("Зарядка");
    }

    @Test
    void doesNotHandOutTheSameIdTwice() {
        assertThat(repository.nextId()).isNotEqualTo(repository.nextId());
    }

    private static Task dated(long id, String title, String dueAt) {
        return Task.create(
                id,
                FAMILY_A,
                title,
                MOM,
                new Assignee(KID, Role.CHILD),
                Instant.parse(dueAt),
                CREATED);
    }

    /**
     * Отказ снимает дело с одного, а не со всех, — и выборка обязана это отражать.
     *
     * <p>⚠️ Проверяется на реальном SQL, а не на фейке. Условие «отказавшийся выпадает, пока дело
     * открыто» живёт внутри {@code exists (...)}, и ошибка в нём выглядела бы как пропавшее дело у
     * второго исполнителя — то есть ровно как поломка фичи, ради которой всё делалось.
     */
    @Test
    void aRefusalRemovesTheTaskFromOneListButNotFromTheOther() {
        Task task =
                repository.save(
                        Task.create(
                                100L,
                                FAMILY_A,
                                "Отвезти к врачу",
                                MOM,
                                List.of(new Assignee(MOM, Role.PARENT), new Assignee(KID, Role.CHILD)),
                                DUE,
                                CREATED));
        task.decline(Actor.member(KID, FAMILY_A, Role.CHILD), "уроки", DUE);
        repository.save(task);

        assertThat(repository.find(new TaskQuery(FAMILY_A, null, KID, null, OPEN))).isEmpty();
        assertThat(repository.find(new TaskQuery(FAMILY_A, null, MOM, null, OPEN)))
                .extracting(Task::id)
                .containsExactly(100L);
    }

    /** Видеть своё дело отказавшийся продолжает: «от чего я отказался» — законный вопрос. */
    @Test
    void aRefusingChildStillSeesTheTask() {
        Task task =
                repository.save(
                        Task.create(
                                100L,
                                FAMILY_A,
                                "Отвезти к врачу",
                                MOM,
                                List.of(new Assignee(MOM, Role.PARENT), new Assignee(KID, Role.CHILD)),
                                DUE,
                                CREATED));
        task.decline(Actor.member(KID, FAMILY_A, Role.CHILD), "уроки", DUE);
        repository.save(task);

        assertThat(repository.find(new TaskQuery(FAMILY_A, KID, null, null, OPEN)))
                .extracting(Task::id)
                .containsExactly(100L);
    }

    /** Причина отказа лежит у человека, а не у дела: у двоих отказавшихся причины разные. */
    @Test
    void everyRefusalKeepsItsOwnReason() {
        Task task =
                repository.save(
                        Task.create(
                                100L,
                                FAMILY_A,
                                "Отвезти к врачу",
                                MOM,
                                List.of(new Assignee(MOM, Role.PARENT), new Assignee(KID, Role.CHILD)),
                                DUE,
                                CREATED));
        task.decline(Actor.member(KID, FAMILY_A, Role.CHILD), "уроки", DUE);
        task.decline(Actor.member(MOM, FAMILY_A, Role.PARENT), "в командировке", DUE);
        repository.save(task);

        Task loaded = repository.findById(FAMILY_A, 100L).orElseThrow();
        assertThat(loaded.status()).isEqualTo(TaskStatus.DECLINED);
        assertThat(loaded.declineReasonOf(KID)).contains("уроки");
        assertThat(loaded.declineReasonOf(MOM)).contains("в командировке");
    }

    /**
     * Снятый исполнитель исчезает из таблицы, а не остаётся висеть.
     *
     * <p>⚠️ Сохранение вписывает текущих и <b>потом</b> удаляет лишних. Обратный порядок оставлял бы
     * дело без единого исполнителя между двумя запросами, а собрать такое из хранилища нельзя вовсе.
     */
    @Test
    void takingSomeoneOffTheTaskRemovesTheirRow() {
        Task task =
                repository.save(
                        Task.create(
                                100L,
                                FAMILY_A,
                                "Отвезти к врачу",
                                MOM,
                                List.of(new Assignee(MOM, Role.PARENT), new Assignee(KID, Role.CHILD)),
                                DUE,
                                CREATED));
        task.unassign(Actor.member(MOM, FAMILY_A, Role.PARENT), KID);
        repository.save(task);

        assertThat(repository.findById(FAMILY_A, 100L).orElseThrow().assignments())
                .extracting(Assignment::memberId)
                .containsExactly(MOM);
        assertThat(jdbc.sql("select count(*) from task_assignee").query(Long.class).single())
                .isEqualTo(1L);
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
