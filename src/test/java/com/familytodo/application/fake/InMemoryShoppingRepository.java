package com.familytodo.application.fake;

import com.familytodo.application.port.ShoppingRepository;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Фейк списков покупок. Фильтр по семье безусловный — как и в SQL.
 *
 * <p>⚠️ Фейк не проверяет внешние ключи и не знает про {@code id_sequence}: всё, что упирается в
 * схему, обязано иметь интеграционный тест на настоящем SQLite. Здесь проверяется поведение
 * юзкейса, и только оно.
 */
public final class InMemoryShoppingRepository implements ShoppingRepository {

    private final Map<Long, ShoppingItem> items = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public ShoppingItem save(ShoppingItem item) {
        items.put(item.id(), item);
        return item;
    }

    @Override
    public Optional<ShoppingItem> findById(long familyId, long itemId) {
        return Optional.ofNullable(items.get(itemId)).filter(i -> i.familyId() == familyId);
    }

    /** Порядок повторяет ORDER BY репозитория: некупленные выше, дальше по времени и id. */
    @Override
    public List<ShoppingItem> findByList(long familyId, ShoppingList list) {
        List<ShoppingItem> found = new ArrayList<>();
        for (ShoppingItem item : items.values()) {
            if (item.familyId() == familyId && item.list() == list) {
                found.add(item);
            }
        }
        found.sort(
                Comparator.comparing(ShoppingItem::isBought)
                        .thenComparing(ShoppingItem::addedAt)
                        .thenComparing(ShoppingItem::id));
        return found;
    }

    @Override
    public int deleteBought(long familyId, ShoppingList list) {
        List<Long> doomed =
                items.values().stream()
                        .filter(i -> i.familyId() == familyId && i.list() == list && i.isBought())
                        .map(ShoppingItem::id)
                        .toList();
        doomed.forEach(items::remove);
        return doomed.size();
    }
}
