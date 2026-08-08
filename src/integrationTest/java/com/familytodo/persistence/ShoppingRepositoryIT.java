package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcShoppingRepository;
import com.familytodo.domain.Actor;
import com.familytodo.domain.Role;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Списки покупок на настоящем SQLite.
 *
 * <p>⚠️ Фейковый репозиторий здесь не годится принципиально: он не проверяет внешние ключи и не
 * знает про {@code id_sequence}. Ровно так баг приглашений дожил до прода — весь путь {@code
 * redeem} до этого никогда не касался настоящей базы.
 */
class ShoppingRepositoryIT extends AbstractSqliteIT {

    private static final long FAMILY_A = 1L;
    private static final long FAMILY_B = 2L;

    private static final long MOM = 10L;
    private static final long KID = 12L;
    private static final long OUTSIDER = 90L;

    private static final Instant ADDED = Instant.parse("2026-08-08T09:00:00Z");
    private static final Instant BOUGHT = Instant.parse("2026-08-08T12:00:00.123Z");

    private JdbcShoppingRepository repository;

    @BeforeEach
    void seed() {
        repository = new JdbcShoppingRepository(jdbc, new JdbcIdSequence(jdbc));

        insertFamily(FAMILY_A, "Ивановы");
        insertFamily(FAMILY_B, "Петровы");
        insertMember(MOM, FAMILY_A, "Мама", Role.PARENT);
        insertMember(KID, FAMILY_A, "Петя", Role.CHILD);
        insertMember(OUTSIDER, FAMILY_B, "Чужой", Role.PARENT);
    }

    /**
     * ⚠️ Ловит забытую строку в {@code id_sequence} — на {@code V4} её забыли, и проявилось это не
     * при старте, а при первой живой попытке что-нибудь создать.
     */
    @Test
    void theSequenceHandsOutIdsForTheNewTable() {
        long first = repository.nextId();
        long second = repository.nextId();

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void roundTripsEveryField() {
        ShoppingItem saved = repository.save(milk(repository.nextId()));

        ShoppingItem loaded = repository.findById(FAMILY_A, saved.id()).orElseThrow();

        assertThat(loaded.familyId()).isEqualTo(FAMILY_A);
        assertThat(loaded.list()).isEqualTo(ShoppingList.FOOD);
        assertThat(loaded.title()).isEqualTo("Молоко");
        assertThat(loaded.addedBy()).isEqualTo(MOM);
        assertThat(loaded.addedAt()).isEqualTo(ADDED);
        assertThat(loaded.isBought()).isFalse();
        assertThat(loaded.boughtBy()).isNull();
        assertThat(loaded.boughtAt()).isNull();
    }

    /** Доли секунды — тот самый случай, на котором сломалось бы текстовое хранение времени. */
    @Test
    void roundTripsTheBuyerAndSubSecondTime() {
        ShoppingItem item = repository.save(milk(repository.nextId()));
        item.markBought(actor(KID, Role.CHILD), BOUGHT);

        repository.save(item);

        ShoppingItem loaded = repository.findById(FAMILY_A, item.id()).orElseThrow();
        assertThat(loaded.isBought()).isTrue();
        assertThat(loaded.boughtBy()).isEqualTo(KID);
        assertThat(loaded.boughtAt()).isEqualTo(BOUGHT);
    }

    @Test
    void findsOnlyTheRequestedList() {
        repository.save(item(repository.nextId(), FAMILY_A, ShoppingList.FOOD, "Молоко"));
        repository.save(item(repository.nextId(), FAMILY_A, ShoppingList.HOUSEHOLD, "Мыло"));

        assertThat(repository.findByList(FAMILY_A, ShoppingList.FOOD))
                .extracting(ShoppingItem::title)
                .containsExactly("Молоко");
    }

    /** Купленное уходит вниз — порядок задаётся в SQL, а не пересобирается во вьюхе. */
    @Test
    void boughtItemsSinkBelowTheOpenOnes() {
        ShoppingItem bread = repository.save(item(repository.nextId(), FAMILY_A, ShoppingList.FOOD, "Хлеб"));
        repository.save(item(repository.nextId(), FAMILY_A, ShoppingList.FOOD, "Молоко"));
        bread.markBought(actor(MOM, Role.PARENT), BOUGHT);
        repository.save(bread);

        assertThat(repository.findByList(FAMILY_A, ShoppingList.FOOD))
                .extracting(ShoppingItem::title)
                .containsExactly("Молоко", "Хлеб");
    }

    // --- изоляция ---

    @Test
    void anItemOfAnotherFamilyIsNotReturnedByTheList() {
        repository.save(
                ShoppingItem.add(
                        repository.nextId(),
                        FAMILY_B,
                        ShoppingList.FOOD,
                        "Чужое молоко",
                        actorOf(OUTSIDER, FAMILY_B, Role.PARENT),
                        ADDED));

        assertThat(repository.findByList(FAMILY_A, ShoppingList.FOOD)).isEmpty();
    }

    /** Прямой id чужой позиции: фильтр по {@code family_id} стоит в SQL, а не в памяти. */
    @Test
    void anItemOfAnotherFamilyIsNotReachableByItsId() {
        ShoppingItem alien =
                repository.save(
                        ShoppingItem.add(
                                repository.nextId(),
                                FAMILY_B,
                                ShoppingList.FOOD,
                                "Чужое молоко",
                                actorOf(OUTSIDER, FAMILY_B, Role.PARENT),
                                ADDED));

        assertThat(repository.findById(FAMILY_A, alien.id())).isEmpty();
        assertThat(repository.findById(FAMILY_B, alien.id())).isPresent();
    }

    /** Очистка не должна выходить за пределы семьи и списка. */
    @Test
    void clearingBoughtItemsTouchesNeitherOtherListsNorOtherFamilies() {
        ShoppingItem ours = repository.save(item(repository.nextId(), FAMILY_A, ShoppingList.FOOD, "Хлеб"));
        ours.markBought(actor(MOM, Role.PARENT), BOUGHT);
        repository.save(ours);

        ShoppingItem otherList =
                repository.save(item(repository.nextId(), FAMILY_A, ShoppingList.HOUSEHOLD, "Мыло"));
        otherList.markBought(actor(MOM, Role.PARENT), BOUGHT);
        repository.save(otherList);

        ShoppingItem alien =
                repository.save(
                        ShoppingItem.add(
                                repository.nextId(),
                                FAMILY_B,
                                ShoppingList.FOOD,
                                "Чужое",
                                actorOf(OUTSIDER, FAMILY_B, Role.PARENT),
                                ADDED));
        alien.markBought(actorOf(OUTSIDER, FAMILY_B, Role.PARENT), BOUGHT);
        repository.save(alien);

        int removed = repository.deleteBought(FAMILY_A, ShoppingList.FOOD);

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findByList(FAMILY_A, ShoppingList.FOOD)).isEmpty();
        assertThat(repository.findByList(FAMILY_A, ShoppingList.HOUSEHOLD)).hasSize(1);
        assertThat(repository.findByList(FAMILY_B, ShoppingList.FOOD)).hasSize(1);
    }

    @Test
    void clearingLeavesTheOpenItemsAlone() {
        repository.save(item(repository.nextId(), FAMILY_A, ShoppingList.FOOD, "Молоко"));

        assertThat(repository.deleteBought(FAMILY_A, ShoppingList.FOOD)).isZero();
        assertThat(repository.findByList(FAMILY_A, ShoppingList.FOOD)).hasSize(1);
    }

    // --- внешние ключи ---

    /**
     * ⚠️ Проверка держится прагмой {@code foreign_keys=true}: в SQLite ключи по умолчанию выключены,
     * и без неё эта строка вставилась бы молча.
     */
    @Test
    void anItemAddedByAMemberThatDoesNotExistIsRefusedBySchema() {
        ShoppingItem ghost =
                ShoppingItem.restore(
                        repository.nextId(),
                        FAMILY_A,
                        ShoppingList.FOOD,
                        "Молоко",
                        777L,
                        ADDED,
                        null,
                        null);

        assertThatThrownBy(() -> repository.save(ghost))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anItemOfAFamilyThatDoesNotExistIsRefusedBySchema() {
        ShoppingItem ghost =
                ShoppingItem.restore(
                        repository.nextId(), 999L, ShoppingList.FOOD, "Молоко", MOM, ADDED, null, null);

        assertThatThrownBy(() -> repository.save(ghost))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- вспомогательное ---

    private ShoppingItem milk(long id) {
        return item(id, FAMILY_A, ShoppingList.FOOD, "Молоко");
    }

    private ShoppingItem item(long id, long familyId, ShoppingList list, String title) {
        return ShoppingItem.add(id, familyId, list, title, actor(MOM, Role.PARENT), ADDED);
    }

    private static Actor actor(long memberId, Role role) {
        return actorOf(memberId, FAMILY_A, role);
    }

    private static Actor actorOf(long memberId, long familyId, Role role) {
        return Actor.member(memberId, familyId, role);
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
