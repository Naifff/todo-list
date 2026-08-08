package com.familytodo.application;

import com.familytodo.application.port.ShoppingRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Списки покупок.
 *
 * <p>Уведомлений здесь нет ни одного, и это решение, а не упущение: список наполняется десятками
 * мелочей в день, и оповещение на каждую позицию превратило бы полезную вещь в источник шума. В
 * дайджест список тоже не идёт — по той же причине.
 */
@Service
public class ShoppingService {

    /**
     * Сколько позиций принимаем одним сообщением.
     *
     * <p>Ограничение защищает не базу, а экран: клавиатура Telegram не резиновая, и на сотне кнопок
     * сообщение просто не отправится. Вставленный из заметок список на двести строк — промах, а не
     * заказ, и честнее сказать об этом сразу.
     */
    public static final int MAX_ITEMS_PER_MESSAGE = 20;

    private static final Logger log = LoggerFactory.getLogger(ShoppingService.class);

    private final ShoppingRepository items;
    private final Clock clock;

    public ShoppingService(ShoppingRepository items, Clock clock) {
        this.items = items;
        this.clock = clock;
    }

    public List<ShoppingItem> items(Member actor, ShoppingList list) {
        return items.findByList(actor.familyId(), list);
    }

    /**
     * Внести позиции одним сообщением: по строке на позицию.
     *
     * <p>Сообщение принимается целиком или отвергается целиком. Частичный успех непроверяем на
     * глаз: человек увидит «добавлено 3» и не узнает, какая из четырёх строк пропала.
     */
    public List<ShoppingItem> add(Member actor, ShoppingList list, String message) {
        List<String> titles = parse(message);
        if (titles.size() > MAX_ITEMS_PER_MESSAGE) {
            throw new IllegalArgumentException(
                    "more than " + MAX_ITEMS_PER_MESSAGE + " items in one message");
        }

        Instant now = clock.instant();
        List<ShoppingItem> added = new ArrayList<>(titles.size());
        for (String title : titles) {
            // проверка длины живёт в домене и сработает здесь же, до первой записи
            added.add(
                    ShoppingItem.add(
                            items.nextId(), actor.familyId(), list, title, actor.asActor(), now));
        }

        added.forEach(items::save);
        log.info("shopping items added family={} list={} count={}", actor.familyId(), list, added.size());
        return added;
    }

    /** Переключить «куплено». Одна кнопка на оба направления: в списке это одно и то же действие. */
    public ShoppingItem toggle(Member actor, long itemId) {
        ShoppingItem item = visible(actor, itemId);
        if (item.isBought()) {
            item.unmarkBought(actor.asActor());
        } else {
            item.markBought(actor.asActor(), clock.instant());
        }
        return items.save(item);
    }

    /** Убрать купленное. Жёсткое удаление строк — история покупок никому не нужна. */
    public int clearBought(Member actor, ShoppingList list) {
        int removed = items.deleteBought(actor.familyId(), list);
        log.info("shopping items cleared family={} list={} count={}", actor.familyId(), list, removed);
        return removed;
    }

    /**
     * ⚠️ Позиция грузится с {@code familyId} актора, а не по одному лишь id: {@code callback_data}
     * недоверенный, и чужой номер обязан выглядеть как несуществующий, а не как запретный.
     */
    private ShoppingItem visible(Member actor, long itemId) {
        return items.findById(actor.familyId(), itemId)
                .orElseThrow(() -> new DomainException.NotFound("shopping item is not found"));
    }

    /**
     * Строки сообщения в заголовки позиций: обрезка пробелов, отсев пустых, снятие повторов.
     *
     * <p>Повторы снимаются без учёта регистра и только <b>внутри одного сообщения</b>: список пишут
     * в спешке, и «молоко» дважды подряд — описка. А тот же товар, добавленный через день, законен:
     * молоко кончается снова.
     */
    private static List<String> parse(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> titles = new ArrayList<>();
        for (String line : message.split("\\R")) {
            String title = line.strip();
            if (title.isEmpty()) {
                continue;
            }
            if (seen.add(title.toLowerCase(Locale.ROOT))) {
                titles.add(title);
            }
        }
        return titles;
    }
}
