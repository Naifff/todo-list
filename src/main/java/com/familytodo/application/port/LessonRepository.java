package com.familytodo.application.port;

import com.familytodo.domain.Lesson;
import java.util.List;

/**
 * Порт хранилища расписания.
 *
 * <p>Тот же инвариант, что у задач и серий: всё, что возвращает уроки, принимает {@code familyId}
 * первым аргументом. Изоляция между семьями держится формой интерфейса, а не аккуратностью
 * вызывающего.
 */
public interface LessonRepository {

    long nextId();

    /**
     * Заменить расписание участника целиком.
     *
     * <p>Именно заменить, а не дополнить: после третьей правки «дополнением» никто не знает, что
     * лежит в базе. Замена — одна операция, чтобы между стиранием и вставкой не осталось состояния,
     * в котором у ребёнка нет расписания вовсе.
     *
     * @return сколько уроков было до замены
     */
    int replace(long familyId, long memberId, List<Lesson> lessons);

    List<Lesson> findByMember(long familyId, long memberId);

    /** Расписание всех, кого этому участнику положено видеть. */
    List<Lesson> findVisible(long familyId, Long visibleToMemberId);
}
