package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.view.TaskListPresenter;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Списки дел: {@code /my}, {@code /assigned}, {@code /all}. */
@Component
public class TaskListHandler implements CommandHandler {

    private final TaskListPresenter lists;
    private final BotSender sender;

    public TaskListHandler(TaskListPresenter lists, BotSender sender) {
        this.lists = lists;
        this.sender = sender;
    }

    @Override
    public Set<String> commands() {
        return Set.of("my", "assigned", "all");
    }

    @Override
    public void handle(BotRequest request) {
        Member member = request.requireMember();
        String command = request.command().orElseThrow();

        switch (command) {
            case "my" -> lists.send(request.chatId(), member, TaskListView.Kind.MINE);
            case "assigned" ->
                    lists.send(request.chatId(), member, TaskListView.Kind.REQUESTED);
            case "all" -> showAll(request, member);
            default -> throw new IllegalStateException("unexpected command " + command);
        }
    }

    /**
     * Ограничение команды, а не видимости: {@code TaskQuery} и так сужается для ребёнка. Отдельная
     * проверка нужна, чтобы ребёнок получил внятный отказ вместо списка из двух своих дел под
     * заголовком «Все дела семьи» — иначе заголовок врёт.
     */
    private void showAll(BotRequest request, Member member) {
        if (member.role() != Role.PARENT) {
            sender.send(request.chatId(), Texts.ALL_IS_FOR_PARENTS);
            return;
        }
        lists.send(request.chatId(), member, TaskListView.Kind.ALL);
    }
}
