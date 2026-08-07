package com.familytodo.adapter.telegram;

import com.familytodo.domain.Member;
import java.util.Optional;

/**
 * Разобранный апдейт: кто написал, откуда и что.
 *
 * <p>{@code member} пуст, если отправитель не состоит ни в одной семье. Незнакомцу бот не
 * раскрывает ничего — ни что бот существует для кого-то ещё, ни какие команды у него есть.
 *
 * @param telegramUserId идентификатор отправителя, единственное, что известно до обращения к БД
 * @param chatId личный чат — именно туда уходят напоминания
 * @param text текст сообщения; для нажатия кнопки пуст
 * @param command команда без слэша и без аргумента, например {@code start}
 * @param commandArgument то, что шло за командой: {@code /start inv_КОД} → {@code inv_КОД}
 */
public record BotRequest(
        long telegramUserId,
        long chatId,
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
