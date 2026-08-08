package com.familytodo.application.port;

import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.util.List;
import java.util.Optional;

/**
 * Порт хранилища списков покупок.
 *
 * <p>Тот же инвариант, что и у {@link TaskRepository}: всё, что возвращает или удаляет позиции,
 * принимает {@code familyId} <b>первым аргументом</b>. Исключений здесь нет вовсе — в отличие от
 * серий, у списков покупок нет ни одной системной джобы, которая обходила бы все семьи подряд.
 *
 * <p>Видимость внутри семьи не сужается ролью: оба списка целиком видны всем, включая {@code
 * CHILD}. Это сознательное исключение из правила задач, где ребёнок видит только свои дела —
 * список покупок общий по смыслу.
 */
public interface ShoppingRepository {

    long nextId();

    ShoppingItem save(ShoppingItem item);

    Optional<ShoppingItem> findById(long familyId, long itemId);

    /** Позиции одного списка: некупленные выше купленных, внутри групп — по времени добавления. */
    List<ShoppingItem> findByList(long familyId, ShoppingList list);

    /**
     * Удалить купленные позиции списка.
     *
     * <p>Жёсткое удаление строк, как и {@code delete} у задач: история покупок никому не нужна, а
     * «куплено» и так видно до очистки.
     *
     * @return сколько строк убрано — бот показывает это в ответе
     */
    int deleteBought(long familyId, ShoppingList list);
}
