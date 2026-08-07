package com.familytodo.adapter.persistence;

import com.familytodo.application.port.FamilyRepository;
import com.familytodo.domain.Family;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFamilyRepository implements FamilyRepository {

    private static final String SEQUENCE = "family";

    /**
     * Время дайджеста пишем строго как {@code HH:mm}. {@code LocalTime.toString()} добавил бы
     * секунды, если бы они были ненулевыми, и формат в колонке поехал бы.
     */
    private static final DateTimeFormatter DIGEST_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String SELECT =
            "select id, name, timezone, digest_time, last_digest_date, created_at from family";

    private static final String UPSERT =
            """
            insert into family (id, name, timezone, digest_time, last_digest_date, created_at)
            values (?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                name             = excluded.name,
                timezone         = excluded.timezone,
                digest_time      = excluded.digest_time,
                last_digest_date = excluded.last_digest_date
            """;

    private final JdbcClient jdbc;
    private final JdbcIdSequence sequence;

    public JdbcFamilyRepository(JdbcClient jdbc, JdbcIdSequence sequence) {
        this.jdbc = jdbc;
        this.sequence = sequence;
    }

    @Override
    public long nextId() {
        return sequence.next(SEQUENCE);
    }

    @Override
    public Family save(Family family) {
        jdbc.sql(UPSERT)
                .params(
                        family.id(),
                        family.name(),
                        family.timezone().getId(),
                        family.digestTime().format(DIGEST_TIME),
                        family.lastDigestDate().toString(),
                        Instants.write(family.createdAt()))
                .update();
        return family;
    }

    @Override
    public Optional<Family> findById(long familyId) {
        return jdbc.sql(SELECT + " where id = ?")
                .param(familyId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<Family> findAll() {
        return jdbc.sql(SELECT + " order by id").query(MAPPER).list();
    }

    private static final RowMapper<Family> MAPPER = JdbcFamilyRepository::mapRow;

    private static Family mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Family.restore(
                rs.getLong("id"),
                rs.getString("name"),
                ZoneId.of(rs.getString("timezone")),
                LocalTime.parse(rs.getString("digest_time")),
                LocalDate.parse(rs.getString("last_digest_date")),
                Instants.read(rs, "created_at"));
    }
}
