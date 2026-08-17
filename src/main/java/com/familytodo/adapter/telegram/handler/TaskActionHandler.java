package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.DialogHandler;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.adapter.telegram.view.AgendaPresenter;
import com.familytodo.adapter.telegram.view.TaskCardView;
import com.familytodo.adapter.telegram.view.TaskListPresenter;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskService;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Действия над задачей: «Готово», «Не могу», «Вернуть» и возврат к списку.
 *
 * <p>Каждое нажатие приходит строкой, которую клиент может подделать: id чужой задачи подставляется
 * тривиально. Поэтому обработчик <b>всегда заново грузит задачу в границах семьи актора</b> и
 * отдаёт переход домену. Кнопки отражают права, но не обеспечивают их.
 */
@Component
public class TaskActionHandler implements CallbackHandler, DialogHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskActionHandler.class);

    private static final int MAX_REASON_LENGTH = 200;

    private final TaskService tasks;
    private final FamilyService families;
    private final TaskListPresenter lists;
    private final AgendaPresenter agenda;
    private final DialogStateStore dialogs;
    private final BotSender sender;
    private final Clock clock;

    public TaskActionHandler(
            TaskService tasks,
            FamilyService families,
            TaskListPresenter lists,
            AgendaPresenter agenda,
            DialogStateStore dialogs,
            BotSender sender,
            Clock clock) {
        this.tasks = tasks;
        this.families = families;
        this.lists = lists;
        this.agenda = agenda;
        this.dialogs = dialogs;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public String prefix() {
        return TaskCardView.PREFIX;
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        Member actor = request.requireMember();

        if (TaskCardView.BACK.equals(data.action())) {
            back(request, actor, data.argument());
            return;
        }
        if (TaskCardView.PAGE.equals(data.action())) {
            turnPage(request, actor, data.argument());
            return;
        }

        TaskRef ref = TaskRef.parse(data.argument());
        switch (data.action()) {
            case TaskCardView.CARD -> showCard(request, actor, ref);
            case TaskCardView.DONE -> complete(request, actor, ref);
            case TaskCardView.REOPEN -> reopen(request, actor, ref);
            case TaskCardView.DECLINE -> askReason(request, actor, ref);
            default -> log.warn("unknown task action {}", data.action());
        }
    }

    /** Причина отказа приходит отдельным сообщением, поэтому карточку отправляем заново. */
    @Override
    public boolean continueDialog(BotRequest request) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.AwaitingDeclineReason awaiting)) {
            return false;
        }

        String reason = request.text().trim();
        if (reason.isBlank()) {
            sender.send(request.chatId(), Texts.ASK_DECLINE_REASON);
            return true;
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            sender.send(request.chatId(), Texts.DECLINE_REASON_TOO_LONG);
            return true;
        }

        Member actor = request.requireMember();
        Task declined = tasks.decline(actor, awaiting.taskId(), reason);
        dialogs.clear(request.telegramUserId());
        log.info("task {} declined by member {}", declined.id(), actor.id());

        sender.send(
                request.chatId(),
                card(declined, actor),
                TaskCardView.keyboard(declined, actor.asActor(), awaiting.ref()));
        return true;
    }

    private void showCard(BotRequest request, Member actor, TaskRef ref) {
        edit(request, actor, tasks.findVisible(actor, ref.taskId()), ref);
    }

    private void complete(BotRequest request, Member actor, TaskRef ref) {
        Task task = tasks.complete(actor, ref.taskId());
        log.info("task {} completed by member {}", task.id(), actor.id());
        edit(request, actor, task, ref);
    }

    private void reopen(BotRequest request, Member actor, TaskRef ref) {
        Task task = tasks.reopen(actor, ref.taskId());
        log.info("task {} reopened by member {}", task.id(), actor.id());
        edit(request, actor, task, ref);
    }

    /**
     * Право на отказ проверяем <b>до</b> того, как спросим причину: иначе посторонний напишет
     * объяснение и только потом узнает, что ему нельзя.
     */
    private void askReason(BotRequest request, Member actor, TaskRef ref) {
        Task task = tasks.findVisible(actor, ref.taskId());
        if (!task.mayDecline(actor.asActor())) {
            throw new DomainException.NotPermitted("actor may not decline this task");
        }

        dialogs.put(
                request.telegramUserId(),
                new DialogState.AwaitingDeclineReason(ref));
        sender.send(request.chatId(), Texts.ASK_DECLINE_REASON);
    }

    /**
     * Возврат в список — на ту страницу, где лежит это дело.
     *
     * <p>⚠️ Аргумент из одной буквы тоже принимается, и это не мусор: кнопки живут в чате вечно, и
     * сообщение, отправленное до появления страниц, несёт именно такой «← Назад». Оно обязано
     * открывать список, а не ошибку.
     */
    private void back(BotRequest request, Member actor, String argument) {
        // ⚠️ из расписания возврат ведёт в расписание, а не в список всех дел: человек листал
        // неделю, и «← Назад» обязан вернуть его туда же — с тем же горизонтом и на ту страницу
        TaskRef fromAgenda = argument.charAt(0) == 'g' ? TaskRef.parse(argument) : null;
        if (fromAgenda != null) {
            backToAgenda(request, actor, fromAgenda);
            return;
        }

        TaskListView.Kind kind = kindOf(argument.charAt(0));
        Long taskId = argument.length() > 1 ? TaskRef.parse(argument).taskId() : null;

        request.messageId()
                .ifPresentOrElse(
                        id -> {
                            if (taskId == null) {
                                lists.edit(request.chatId(), id, actor, kind, 0);
                            } else {
                                lists.editAround(request.chatId(), id, actor, kind, taskId);
                            }
                        },
                        () -> lists.send(request.chatId(), actor, kind));
    }

    private void backToAgenda(BotRequest request, Member actor, TaskRef ref) {
        request.messageId()
                .ifPresentOrElse(
                        id ->
                                agenda.editAround(
                                        request.chatId(), id, actor, ref.agendaDays(), ref.taskId()),
                        () -> agenda.send(request.chatId(), actor, ref.agendaDays(), 0));
    }

    /** Листание: буква списка и номер страницы, например {@code a2}. */
    private void turnPage(BotRequest request, Member actor, String argument) {
        TaskListView.Kind kind = kindOf(argument.charAt(0));
        int page;
        try {
            page = Integer.parseInt(argument.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("page is not a number", e);
        }
        if (page < 0) {
            throw new IllegalArgumentException("page is negative");
        }

        request.messageId()
                .ifPresentOrElse(
                        id -> lists.edit(request.chatId(), id, actor, kind, page),
                        () -> lists.send(request.chatId(), actor, kind));
    }

    private static TaskListView.Kind kindOf(char letter) {
        return switch (letter) {
            case 'm' -> TaskListView.Kind.MINE;
            case 'r' -> TaskListView.Kind.REQUESTED;
            case 'a' -> TaskListView.Kind.ALL;
            default -> throw new IllegalArgumentException("unknown list kind");
        };
    }

    /** Нажатие переписывает то же сообщение, а не плодит новые карточки. */
    private void edit(BotRequest request, Member actor, Task task, TaskRef ref) {
        String text = card(task, actor);
        var keyboard = TaskCardView.keyboard(task, actor.asActor(), ref);
        request.messageId()
                .ifPresentOrElse(
                        id -> sender.edit(request.chatId(), id, text, keyboard),
                        () -> sender.send(request.chatId(), text, keyboard));
    }

    private String card(Task task, Member viewer) {
        List<Member> roster = families.roster(viewer);
        Map<Long, Member> byId =
                roster.stream().collect(Collectors.toMap(Member::id, Function.identity()));
        return TaskCardView.render(
                task, byId, families.family(viewer).timezone(), clock.instant());
    }
}
