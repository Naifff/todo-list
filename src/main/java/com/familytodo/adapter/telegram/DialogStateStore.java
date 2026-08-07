package com.familytodo.adapter.telegram;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Незавершённые диалоги, по одному на пользователя Telegram.
 *
 * <p>Caffeine, а не {@code ConcurrentHashMap}: {@code expireAfterWrite} сам вытесняет протухшие
 * записи. Своя карта на процессе, живущем месяцами, копила бы брошенные диалоги — каждый, кто начал
 * создавать задачу и передумал, оставлял бы запись навсегда.
 */
@Component
public class DialogStateStore {

    public static final Duration TTL = Duration.ofMinutes(15);

    private final Cache<Long, DialogState> states =
            Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000).build();

    public Optional<DialogState> get(long telegramUserId) {
        return Optional.ofNullable(states.getIfPresent(telegramUserId));
    }

    public void put(long telegramUserId, DialogState state) {
        states.put(telegramUserId, state);
    }

    public void clear(long telegramUserId) {
        states.invalidate(telegramUserId);
    }
}
