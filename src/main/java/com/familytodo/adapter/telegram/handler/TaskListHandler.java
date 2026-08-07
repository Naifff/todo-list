package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.TaskService;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Списки дел: {@code /my}, {@code /assigned}, {@code /all}. */
@Component
public class TaskListHandler implements CommandHandler {

    private final TaskService tasks;
    private final FamilyService families;
    private final BotSender sender;
    private final Clock clock;

    public TaskListHandler(
            TaskService tasks, FamilyService families, BotSender sender, Clock clock) {
        this.tasks = tasks;
        this.families = families;
        this.sender = sender;
        this.clock = clock;
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
            case "my" ->
                    show(
                            request,
                            member,
                            TaskQuery.assignedTo(member),
                            TaskListView.Kind.MINE,
                            Texts.MINE_HEADER,
                            Texts.MINE_EMPTY);
            case "assigned" ->
                    show(
                            request,
                            member,
                            TaskQuery.createdBy(member),
                            TaskListView.Kind.REQUESTED,
                            Texts.REQUESTED_HEADER,
                            Texts.REQUESTED_EMPTY);
            case "all" -> showAll(request, member);
            default -> throw new IllegalStateException("unexpected command " + command);
        }
    }

    /**
     * Ограничение команды, а не видимости: {@code TaskQuery} и так сужается для ребёнка. Отдельная
     * проверка здесь нужна, чтобы ребёнок получил внятный отказ вместо списка из двух своих дел под
     * заголовком «Все дела семьи».
     */
    private void showAll(BotRequest request, Member member) {
        if (member.role() != Role.PARENT) {
            sender.send(request.chatId(), Texts.ALL_IS_FOR_PARENTS);
            return;
        }
        show(
                request,
                member,
                TaskQuery.visibleTo(member),
                TaskListView.Kind.ALL,
                Texts.ALL_HEADER,
                Texts.ALL_EMPTY);
    }

    private void show(
            BotRequest request,
            Member member,
            TaskQuery query,
            TaskListView.Kind kind,
            String header,
            String empty) {

        List<Task> found = tasks.find(query);
        if (found.isEmpty()) {
            sender.send(request.chatId(), empty);
            return;
        }

        ZoneId zone = families.family(member).timezone();
        Map<Long, Member> byId =
                families.roster(member).stream()
                        .collect(Collectors.toMap(Member::id, Function.identity()));

        // одно сообщение, а не N: лента, в которой ничего не найти, — та самая
        // проблема, ради которой всё и делается
        sender.send(
                request.chatId(),
                TaskListView.render(header, found, byId, kind, zone, clock.instant()));
    }
}
