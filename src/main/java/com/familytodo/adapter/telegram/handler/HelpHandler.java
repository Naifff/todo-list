package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.view.Texts;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Справка — только своим.
 *
 * <p>Отдельный класс, а не команда внутри {@link StartHandler}: тот разрешён незнакомцам целиком, и
 * {@code /help} рядом с ним раскрывал бы посторонним весь список команд.
 */
@Component
public class HelpHandler implements CommandHandler {

    private final BotSender sender;

    public HelpHandler(BotSender sender) {
        this.sender = sender;
    }

    @Override
    public Set<String> commands() {
        return Set.of("help");
    }

    @Override
    public void handle(BotRequest request) {
        sender.send(request.chatId(), Texts.MAIN_MENU);
    }
}
