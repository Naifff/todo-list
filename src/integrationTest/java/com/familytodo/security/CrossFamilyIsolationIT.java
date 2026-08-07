package com.familytodo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.adapter.persistence.JdbcFamilyRepository;
import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcMemberRepository;
import com.familytodo.adapter.persistence.JdbcTaskRepository;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.TaskService;
import com.familytodo.support.NoOpNotifier;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import com.familytodo.persistence.AbstractSqliteIT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ключевой тест безопасности всего проекта, на реальном SQL и целиком через юзкейсы.
 *
 * <p>Моделируется единственный способ, которым чужая задача вообще достижима: подделанный {@code
 * callback_data}. Кнопки её не покажут, в списке её нет — но идентификатор угадать можно, и
 * нажатие приходит прямым запросом с чужим id.
 *
 * <p>Ожидание строже, чем «отказано»: чужая задача должна быть <b>не найдена</b>. Разница видна
 * снаружи — отказ подтверждает, что задача с таким id существует.
 */
class CrossFamilyIsolationIT extends AbstractSqliteIT {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-07T16:00:00Z");

    private JdbcTaskRepository taskRepository;
    private JdbcMemberRepository memberRepository;
    private TaskService service;

    private Member momA;
    private Member kidA;
    private Member otherKidA;
    private Member momB;
    private Task taskOfFamilyA;

    @BeforeEach
    void setUpTwoFamilies() {
        JdbcIdSequence sequence = new JdbcIdSequence(jdbc);
        JdbcFamilyRepository familyRepository = new JdbcFamilyRepository(jdbc, sequence);
        memberRepository = new JdbcMemberRepository(jdbc, sequence);
        taskRepository = new JdbcTaskRepository(jdbc, sequence);
        service =
                new TaskService(
                        taskRepository,
                        memberRepository,
                        new NoOpNotifier(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        Family familyA =
                familyRepository.save(
                        Family.create(familyRepository.nextId(), "Ивановы", MOSCOW, NOW));
        Family familyB =
                familyRepository.save(
                        Family.create(familyRepository.nextId(), "Петровы", MOSCOW, NOW));

        momA = join(familyA, 100000001L, "Мама А", Role.PARENT);
        kidA = join(familyA, 512034877L, "Петя А", Role.CHILD);
        otherKidA = join(familyA, 512034878L, "Вася А", Role.CHILD);
        momB = join(familyB, 600000001L, "Мама Б", Role.PARENT);

        taskOfFamilyA = service.create(momA, kidA.id(), "Вынести мусор", DUE);
    }

    @Test
    void strangerCannotCompleteForeignTask() {
        assertThatThrownBy(() -> service.complete(momB, taskOfFamilyA.id()))
                .isInstanceOf(DomainException.NotFound.class);
        assertStillOpen();
    }

    @Test
    void strangerCannotDeclineForeignTask() {
        assertThatThrownBy(() -> service.decline(momB, taskOfFamilyA.id(), "не хочу"))
                .isInstanceOf(DomainException.NotFound.class);
        assertStillOpen();
    }

    @Test
    void strangerCannotReopenForeignTask() {
        service.complete(kidA, taskOfFamilyA.id());

        assertThatThrownBy(() -> service.reopen(momB, taskOfFamilyA.id()))
                .isInstanceOf(DomainException.NotFound.class);
        assertThat(reload().status()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void strangerCannotEditForeignTask() {
        assertThatThrownBy(() -> service.edit(momB, taskOfFamilyA.id(), "переписано", null))
                .isInstanceOf(DomainException.NotFound.class);
        assertThat(reload().title()).isEqualTo("Вынести мусор");
        assertThat(reload().dueAt()).isEqualTo(DUE);
    }

    @Test
    void strangerCannotDeleteForeignTask() {
        assertThatThrownBy(() -> service.delete(momB, taskOfFamilyA.id()))
                .isInstanceOf(DomainException.NotFound.class);
        assertStillOpen();
    }

    @Test
    void foreignTaskNeverAppearsInLists() {
        assertThat(service.find(TaskQuery.visibleTo(momB))).isEmpty();
        assertThat(service.find(TaskQuery.assignedTo(momB))).isEmpty();
        assertThat(service.find(TaskQuery.createdBy(momB))).isEmpty();
    }

    @Test
    void strangerCannotAssignTaskToSomeoneElsesFamilyMember() {
        assertThatThrownBy(() -> service.create(momB, kidA.id(), "Чужое поручение", DUE))
                .isInstanceOf(DomainException.NotFound.class);

        assertThat(taskRepository.find(TaskQuery.visibleTo(kidA)))
                .extracting(Task::id)
                .containsExactly(taskOfFamilyA.id());
    }

    /**
     * Вторая ось изоляции: внутри одной семьи. Задача найдена, но посторонний ребёнок к ней не
     * причастен — здесь ответ уже «нельзя», потому что существование задачи в своей семье не
     * секрет.
     */
    @Test
    void uninvolvedChildOfTheSameFamilyIsDenied() {
        assertThatThrownBy(() -> service.complete(otherKidA, taskOfFamilyA.id()))
                .isInstanceOf(DomainException.NotPermitted.class);
        assertStillOpen();
        assertThat(service.find(TaskQuery.visibleTo(otherKidA))).isEmpty();
    }

    private void assertStillOpen() {
        Task reloaded = reload();
        assertThat(reloaded.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(reloaded.closedAt()).isNull();
    }

    private Task reload() {
        return taskRepository
                .findById(taskOfFamilyA.familyId(), taskOfFamilyA.id())
                .orElseThrow(() -> new AssertionError("задача исчезла"));
    }

    private Member join(Family family, long telegramUserId, String name, Role role) {
        return memberRepository.save(
                Member.join(
                        memberRepository.nextId(),
                        family.id(),
                        telegramUserId,
                        telegramUserId,
                        name,
                        role,
                        NOW));
    }

}
