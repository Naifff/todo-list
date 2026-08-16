package com.familytodo.adapter.persistence;

import com.familytodo.application.port.LessonRepository;
import com.familytodo.domain.Lesson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Расписание в SQLite.
 *
 * <p>Как и у серий, время суток хранится текстом {@code HH:MM} по календарю семьи: момент считается
 * при отрисовке, а не при записи. Даты действия — тоже текстом {@code YYYY-MM-DD}: это календарные
 * дни, а не моменты, и лексикографический порядок у них совпадает с хронологическим.
 */
@Repository
public class JdbcLessonRepository implements LessonRepository {

    private static final String SEQUENCE = "lesson";

    /** Строго {@code HH:mm}: ненулевые секунды поехали бы в формат колонки. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String SELECT =
            """
            select l.id, l.family_id, l.member_id, l.day_of_week, l.starts_at, l.ends_at,
                   l.subject, l.valid_from, l.valid_to, l.created_at
            from lesson l
            """;

    private final JdbcClient jdbc;
    private final JdbcIdSequence sequence;

    public JdbcLessonRepository(JdbcClient jdbc, JdbcIdSequence sequence) {
        this.jdbc = jdbc;
        this.sequence = sequence;
    }

    @Override
    public long nextId() {
        return sequence.next(SEQUENCE);
    }

    /**
     * ⚠️ Стираем и вставляем в одном методе, а не двумя вызовами из юзкейса: между ними ребёнок
     * оставался бы без расписания вовсе, и оборвавшийся запрос стёр бы учебный год без замены.
     */
    @Override
    public int replace(long familyId, long memberId, List<Lesson> lessons) {
        int removed =
                jdbc.sql("delete from lesson where family_id = ? and member_id = ?")
                        .params(familyId, memberId)
                        .update();

        for (Lesson lesson : lessons) {
            jdbc.sql(
                            """
                            insert into lesson (id, family_id, member_id, day_of_week, starts_at,
                                                ends_at, subject, valid_from, valid_to, created_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            lesson.id(),
                            lesson.familyId(),
                            lesson.memberId(),
                            lesson.day().getValue(),
                            lesson.startsAt().format(TIME),
                            lesson.endsAt().format(TIME),
                            lesson.subject(),
                            lesson.validFrom().toString(),
                            lesson.validTo() == null ? null : lesson.validTo().toString(),
                            Instants.write(lesson.createdAt()))
                    .update();
        }
        return removed;
    }

    @Override
    public List<Lesson> findByMember(long familyId, long memberId) {
        return jdbc.sql(SELECT + " where l.family_id = ? and l.member_id = ? order by l.day_of_week, l.starts_at")
                .params(familyId, memberId)
                .query(JdbcLessonRepository::mapRow)
                .list();
    }

    private static Lesson mapRow(ResultSet rs, int rowNum) throws SQLException {
        String validTo = rs.getString("valid_to");
        return Lesson.restore(
                rs.getLong("id"),
                rs.getLong("family_id"),
                rs.getLong("member_id"),
                DayOfWeek.of(rs.getInt("day_of_week")),
                LocalTime.parse(rs.getString("starts_at")),
                LocalTime.parse(rs.getString("ends_at")),
                rs.getString("subject"),
                LocalDate.parse(rs.getString("valid_from")),
                validTo == null ? null : LocalDate.parse(validTo),
                Instants.read(rs, "created_at"));
    }
}
