package com.familytodo.adapter.telegram;

import com.familytodo.domain.Member;
import java.util.Optional;

/**
 * Разобранный апдейт: кто написал, откуда и что.
 *
 * <p>{@code member} пуст, если отправитель не состоит ни в одной семье — либо не состоял никогда,
 * либо был исключён. Незнакомцу бот не раскрывает ничего: ни что обслуживает кого-то ещё, ни какие
 * у него есть команды.
 *
 * @param telegramUserId идентификатор отправителя, единственное, что известно до обращения к БД
 * @param chatId личный чат — именно туда уходят напоминания
 * @param displayName имя из профиля Telegram; нужно при первом входе в семью
 * @param text текст сообщения; для нажатия кнопки пуст
 * @param command команда без слэша и без аргумента, например {@code start}
 * @param commandArgument то, что шло за командой: {@code /start inv_КОД} → {@code inv_КОД}
 */
public record BotRequest(
        long telegramUserId,
        long chatId,
        String displayName,
        Optional<Member> member,
        String text,
        Optional<String> command,
        Optional<String> commandArgument,
        Optional<Integer> messageId,
        Optional<String> callbackQueryId) {

    public boolean isCallback() {
        return callbackQueryId.isPresent();
    }

    public Member requireMember() {
        return member.orElseThrow(() -> new IllegalStateException("member is not resolved"));
    }
}
