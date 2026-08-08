package com.familytodo.adapter.persistence;

import com.familytodo.application.port.ShoppingRepository;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Списки покупок в SQLite. */
@Repository
public class JdbcShoppingRepository implements ShoppingRepository {

    private static final String SEQUENCE = "shopping_item";

    private static final String SELECT =
            """
            select id, family_id, list_kind, title, added_by, added_at, bought_by, bought_at
            from shopping_item
            """;

    private static final String UPSERT =
            """
            insert into shopping_item
              (id, family_id, list_kind, title, added_by, added_at, bought_by, bought_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                bought_by = excluded.bought_by,
                bought_at = excluded.bought_at
            """;

    /**
     * Купленные уходят вниз прямо в запросе, а не пересобираются во вьюхе: порядок это свойство
     * списка, и повторять его в каждом месте отрисовки — способ однажды забыть.
     *
     * <p>{@code id} в хвосте сортировки нужен для устойчивости: многострочное добавление кладёт
     * несколько позиций одним и тем же {@code added_at}, и без него их порядок задавала бы удача.
     */
    private static final String ORDER =
            " order by case when bought_at is null then 0 else 1 end, added_at, id";

    private final JdbcClient jdbc;
    private final JdbcIdSequence sequence;

    public JdbcShoppingRepository(JdbcClient jdbc, JdbcIdSequence sequence) {
        this.jdbc = jdbc;
        this.sequence = sequence;
    }

    @Override
    public long nextId() {
        return sequence.next(SEQUENCE);
    }

    @Override
    public ShoppingItem save(ShoppingItem item) {
        jdbc.sql(UPSERT)
                .params(
                        item.id(),
                        item.familyId(),
                        item.list().name(),
                        item.title(),
                        item.addedBy(),
                        item.addedAt().toEpochMilli(),
                        item.boughtBy(),
                        Instants.write(item.boughtAt()))
                .update();
        return item;
    }

    @Override
    public Optional<ShoppingItem> findById(long familyId, long itemId) {
        return jdbc.sql(SELECT + " where family_id = ? and id = ?")
                .params(familyId, itemId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<ShoppingItem> findByList(long familyId, ShoppingList list) {
        return jdbc.sql(SELECT + " where family_id = ? and list_kind = ?" + ORDER)
                .params(familyId, list.name())
                .query(MAPPER)
                .list();
    }

    @Override
    public int deleteBought(long familyId, ShoppingList list) {
        return jdbc.sql(
                        """
                        delete from shopping_item
                        where family_id = ? and list_kind = ? and bought_at is not null
                        """)
                .params(familyId, list.name())
                .update();
    }

    private static final RowMapper<ShoppingItem> MAPPER = JdbcShoppingRepository::map;

    private static ShoppingItem map(ResultSet rs, int rowNum) throws SQLException {
        // wasNull относится к последнему чтению, поэтому признак снимается сразу, а не в
        // списке аргументов ниже: между ними успели бы вклиниться added_by и added_at
        long buyer = rs.getLong("bought_by");
        Long boughtBy = rs.wasNull() ? null : buyer;

        return ShoppingItem.restore(
                rs.getLong("id"),
                rs.getLong("family_id"),
                ShoppingList.valueOf(rs.getString("list_kind")),
                rs.getString("title"),
                rs.getLong("added_by"),
                Instants.read(rs, "added_at"),
                boughtBy,
                Instants.read(rs, "bought_at"));
    }
}
