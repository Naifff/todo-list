package com.familytodo.adapter.persistence;

import com.familytodo.application.port.InviteRepository;
import com.familytodo.domain.Invite;
import com.familytodo.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInviteRepository implements InviteRepository {

    private static final String SEQUENCE = "invite";

    private static final String SELECT =
            "select id, family_id, code, role, created_by, expires_at, used_by, used_at from invite";

    private static final String UPSERT =
            """
            insert into invite (id, family_id, code, role, created_by, expires_at, used_by, used_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                used_by = excluded.used_by,
                used_at = excluded.used_at
            """;

    private final JdbcClient jdbc;
    private final JdbcIdSequence sequence;

    public JdbcInviteRepository(JdbcClient jdbc, JdbcIdSequence sequence) {
        this.jdbc = jdbc;
        this.sequence = sequence;
    }

    @Override
    public long nextId() {
        return sequence.next(SEQUENCE);
    }

    @Override
    public Invite save(Invite invite) {
        jdbc.sql(UPSERT)
                .params(
                        invite.id(),
                        invite.familyId(),
                        invite.code(),
                        invite.role().name(),
                        invite.createdBy(),
                        Instants.write(invite.expiresAt()),
                        invite.usedBy(),
                        Instants.write(invite.usedAt()))
                .update();
        return invite;
    }

    @Override
    public Optional<Invite> findByCode(String code) {
        return jdbc.sql(SELECT + " where code = ?").param(code).query(MAPPER).optional();
    }

    private static final RowMapper<Invite> MAPPER = JdbcInviteRepository::mapRow;

    private static Invite mapRow(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() относится к последнему прочитанному столбцу, поэтому проверка
        // обязана стоять вплотную к чтению — внутри вызова конструктора порядок
        // вычисления аргументов сдвинул бы её на expires_at
        long usedByValue = rs.getLong("used_by");
        Long usedBy = rs.wasNull() ? null : usedByValue;

        return Invite.restore(
                rs.getLong("id"),
                rs.getLong("family_id"),
                rs.getString("code"),
                Role.valueOf(rs.getString("role")),
                rs.getLong("created_by"),
                Instants.read(rs, "expires_at"),
                usedBy,
                Instants.read(rs, "used_at"));
    }
}
