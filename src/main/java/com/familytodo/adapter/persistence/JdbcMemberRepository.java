package com.familytodo.adapter.persistence;

import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemberRepository implements MemberRepository {

    private static final String SEQUENCE = "member";

    private static final String SELECT =
            """
            select id, family_id, telegram_user_id, private_chat_id,
                   display_name, role, status, blocked_bot, created_at
            from member
            """;

    private static final String UPSERT =
            """
            insert into member (id, family_id, telegram_user_id, private_chat_id,
                                display_name, role, status, blocked_bot, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                private_chat_id = excluded.private_chat_id,
                display_name    = excluded.display_name,
                role            = excluded.role,
                status          = excluded.status,
                blocked_bot     = excluded.blocked_bot
            """;

    private final JdbcClient jdbc;
    private final JdbcIdSequence sequence;

    public JdbcMemberRepository(JdbcClient jdbc, JdbcIdSequence sequence) {
        this.jdbc = jdbc;
        this.sequence = sequence;
    }

    @Override
    public long nextId() {
        return sequence.next(SEQUENCE);
    }

    @Override
    public Member save(Member member) {
        jdbc.sql(UPSERT)
                .params(
                        member.id(),
                        member.familyId(),
                        member.telegramUserId(),
                        member.privateChatId(),
                        member.displayName(),
                        member.role().name(),
                        member.status().name(),
                        member.blockedBot() ? 1 : 0,
                        Instants.write(member.createdAt()))
                .update();
        return member;
    }

    @Override
    public Optional<Member> findById(long familyId, long memberId) {
        return jdbc.sql(SELECT + " where family_id = ? and id = ?")
                .params(familyId, memberId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<Member> findByTelegramUserId(long telegramUserId) {
        return jdbc.sql(SELECT + " where telegram_user_id = ?")
                .param(telegramUserId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<Member> findActive(long familyId) {
        return jdbc.sql(SELECT + " where family_id = ? and status = 'ACTIVE' order by id")
                .param(familyId)
                .query(MAPPER)
                .list();
    }

    private static final RowMapper<Member> MAPPER = JdbcMemberRepository::mapRow;

    private static Member mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Member.restore(
                rs.getLong("id"),
                rs.getLong("family_id"),
                rs.getLong("telegram_user_id"),
                rs.getLong("private_chat_id"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                MemberStatus.valueOf(rs.getString("status")),
                rs.getInt("blocked_bot") != 0,
                Instants.read(rs, "created_at"));
    }
}
