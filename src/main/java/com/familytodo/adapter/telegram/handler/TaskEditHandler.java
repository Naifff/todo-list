package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.DialogHandler;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.application.DueDateParser;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.adapter.telegram.view.TaskCardView;
import com.familytodo.adapter.telegram.view.TaskEditView;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskService;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Правка и удаление задачи.
 *
 * <p>Вынесено из карточки отдельным сценарием: смена названия, срока и исполнителя — это диалог
 * размером с {@code /new}, а не одна кнопка.
 *
 * <p>Право на всё это проверяет домен: автор, а для задач ребёнка — любой родитель. Родитель над
 * задачей другого родителя получает отказ.
 */
@Component
public class TaskEditHandler implements CallbackHandler, DialogHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskEditHandler.class);

    private final TaskService tasks;
    private final FamilyService families;
    private final DialogStateStore dialogs;
    private final DueDateParser dueDates;
    private final BotSender sender;
    private final Clock clock;

    public TaskEditHandler(
            TaskService tasks,
            FamilyService families,
            DialogStateStore dialogs,
            DueDateParser dueDates,
            BotSender sender,
            Clock clock) {
        this.tasks = tasks;
        this.families = families;
        this.dialogs = dialogs;
        this.dueDates = dueDates;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public String prefix() {
        return TaskEditView.PREFIX;
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        Member actor = request.requireMember();

        switch (data.action()) {
            case TaskEditView.MENU -> showMenu(request, actor, TaskRef.parse(data.argument()));
            case TaskEditView.TITLE -> askTitle(request, actor, TaskRef.parse(data.argument()));
            case TaskEditView.DUE -> askDue(request, actor, TaskRef.parse(data.argument()));
            case TaskEditView.WHO -> askAssignee(request, actor, TaskRef.parse(data.argument()));
            case TaskEditView.SET_DUE -> setDue(request, actor, data.argument());
            case TaskEditView.SET_WHO -> setAssignee(request, actor, data.longArgument());
            case TaskEditView.SLOT -> askSlot(request, actor, TaskRef.parse(data.argument()));
            case TaskEditView.DELETE -> confirmDeletion(request, actor, TaskRef.parse(data.argument()));
            case TaskEditView.DELETE_OK -> delete(request, actor, TaskRef.parse(data.argument()));
            default -> log.warn("unknown edit action {}", data.action());
        }
    }

    @Override
    public boolean continueDialog(BotRequest request) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        return switch (state) {
            case DialogState.AwaitingNewTitle awaiting -> acceptTitle(request, awaiting);
            case DialogState.AwaitingNewCustomDue awaiting -> acceptCustomDue(request, awaiting);
            case DialogState.AwaitingSlot awaiting -> acceptSlot(request, awaiting);
            case null, default -> false;
        };
    }

    private void showMenu(BotRequest request, Member actor, TaskRef ref) {
        Task task = editable(actor, ref);
        edit(request, card(task, actor), TaskEditView.menu(task, ref.kind()));
    }

    private void askTitle(BotRequest request, Member actor, TaskRef ref) {
        editable(actor, ref);
        dialogs.put(
                request.telegramUserId(),
                new DialogState.AwaitingNewTitle(ref.taskId(), ref.kind()));
        sender.send(request.chatId(), Texts.ASK_TASK_TITLE);
    }

    private void askDue(BotRequest request, Member actor, TaskRef ref) {
        editable(actor, ref);
        dialogs.put(
                request.telegramUserId(), new DialogState.EditingTask(ref.taskId(), ref.kind()));
        edit(request, Texts.ASK_DUE, TaskEditView.dueDates());
    }

    private void askAssignee(BotRequest request, Member actor, TaskRef ref) {
        Task task = editable(actor, ref);
        dialogs.put(
                request.telegramUserId(), new DialogState.EditingTask(ref.taskId(), ref.kind()));
        edit(
                request,
                Texts.ASK_ASSIGNEES_EDIT,
                TaskEditView.assignees(task, families.roster(actor), ref.kind()));
    }

    private void askSlot(BotRequest request, Member actor, TaskRef ref) {
        editable(actor, ref);
        dialogs.put(
                request.telegramUserId(),
                new DialogState.AwaitingSlot(ref.taskId(), ref.kind()));
        sender.send(request.chatId(), Texts.ASK_SLOT);
    }

    private boolean acceptSlot(BotRequest request, DialogState.AwaitingSlot awaiting) {
        Member actor = request.requireMember();
        Task task = tasks.findVisible(actor, awaiting.taskId());
        ZoneId zone = families.family(actor).timezone();

        String input = request.text().trim();
        if ("-".equals(input)) {
            tasks.schedule(actor, task.id(), null, null, null);
            finish(request, actor, tasks.findVisible(actor, task.id()), awaiting.kind());
            return true;
        }

        // ⚠️ Тем же разбором, что и при создании. Раньше здесь был отдельный, который дат не
        // знал вовсе: «27.08» уезжало в место целиком. Заодно ушло расхождение — одно время
        // при создании означало срок, а здесь начало занятого времени
        LocalDate day =
                LocalDate.ofInstant(moment(task) != null ? moment(task) : clock.instant(), zone);
        Optional<DueDateParser.Plan> parsed = dueDates.parsePlan(input, zone, day);

        if (parsed.isEmpty()) {
            sender.send(request.chatId(), Texts.SLOT_NOT_PARSED);
            return true;
        }

        apply(actor, task, parsed.get(), zone);
        finish(request, actor, tasks.findVisible(actor, task.id()), awaiting.kind());
        return true;
    }

    /**
     * Меняем только названное. Экран правит уже существующее дело, и умолчание здесь значит «не
     * трогай», а не «сотри»: «18:00-19:00 парк» не должно стирать дату, о которой договорились.
     * Очищает всё разом только «-».
     */
    private void apply(Member actor, Task task, DueDateParser.Plan plan, ZoneId zone) {
        Instant startsAt = task.startsAt();
        Instant endsAt = task.endsAt();

        if (plan.startsAt() != null) {
            startsAt = plan.startsAt();
            endsAt = plan.endsAt();
        } else if (plan.dueAt() != null && task.isScheduled()) {
            // ⚠️ Одна лишь дата у дела с интервалом — это перенос. Календарь ставит дело по
            // началу интервала: сдвинув только срок, мы оставили бы его на прежнем дне, и
            // «перенёс на 27.08» выглядело бы как «ничего не произошло»
            long shift =
                    ChronoUnit.DAYS.between(
                            LocalDate.ofInstant(task.startsAt(), zone),
                            LocalDate.ofInstant(plan.dueAt(), zone));
            startsAt = task.startsAt().atZone(zone).plusDays(shift).toInstant();
            endsAt =
                    task.endsAt() == null
                            ? null
                            : task.endsAt().atZone(zone).plusDays(shift).toInstant();
        }

        String location = plan.location() != null ? plan.location() : task.location();
        tasks.schedule(actor, task.id(), startsAt, endsAt, location);

        // срок ставим последним: schedule его не трогает, а порядок делает намерение явным
        if (plan.dueAt() != null && !task.isScheduled()) {
            tasks.edit(actor, task.id(), task.title(), plan.dueAt());
        }
    }

    /** Момент дела: начало интервала, а если его нет — срок. */
    private static Instant moment(Task task) {
        return task.startsAt() != null ? task.startsAt() : task.dueAt();
    }

    private boolean acceptTitle(BotRequest request, DialogState.AwaitingNewTitle awaiting) {
        String title = request.text().trim();
        if (title.isBlank()) {
            sender.send(request.chatId(), Texts.ASK_TASK_TITLE);
            return true;
        }
        if (title.length() > Task.MAX_TITLE_LENGTH) {
            sender.send(request.chatId(), Texts.TASK_TITLE_TOO_LONG);
            return true;
        }

        Member actor = request.requireMember();
        Task task = tasks.findVisible(actor, awaiting.taskId());
        Task updated = tasks.edit(actor, task.id(), title, task.dueAt());
        finish(request, actor, updated, awaiting.kind());
        return true;
    }

    private void setDue(BotRequest request, Member actor, String choice) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.EditingTask editing)) {
            expired(request);
            return;
        }

        if ("custom".equals(choice)) {
            dialogs.put(
                    request.telegramUserId(),
                    new DialogState.AwaitingNewCustomDue(editing.taskId(), editing.kind()));
            sender.send(request.chatId(), Texts.ASK_CUSTOM_DUE);
            return;
        }

        ZoneId zone = families.family(actor).timezone();
        Instant dueAt =
                switch (choice) {
                    case "today" -> dueDates.today(zone);
                    case "tomorrow" -> dueDates.tomorrow(zone);
                    case "weekend" -> dueDates.weekend(zone);
                    default -> null;
                };

        Task task = tasks.findVisible(actor, editing.taskId());
        Task updated = tasks.edit(actor, task.id(), task.title(), dueAt);
        finish(request, actor, updated, editing.kind());
    }

    private boolean acceptCustomDue(BotRequest request, DialogState.AwaitingNewCustomDue awaiting) {
        Member actor = request.requireMember();
        Optional<Instant> dueAt =
                dueDates.parse(request.text(), families.family(actor).timezone());
        if (dueAt.isEmpty()) {
            // состояние держим: ошибка в формате, а не отказ от правки
            sender.send(request.chatId(), Texts.DUE_NOT_PARSED);
            return true;
        }

        Task task = tasks.findVisible(actor, awaiting.taskId());
        Task updated = tasks.edit(actor, task.id(), task.title(), dueAt.get());
        finish(request, actor, updated, awaiting.kind());
        return true;
    }

    /**
     * Тап переключает: числится человек исполнителем — снимаем, не числится — добавляем.
     *
     * <p>⚠️ Кто сейчас в списке, спрашиваем у задачи, а не у подписи нажатой кнопки:
     * {@code callback_data} приходит подделываемой строкой, и старая клавиатура у человека на
     * экране могла устареть — состав мог поменять другой родитель со своего телефона.
     *
     * <p>Клавиатура перерисовывается на месте, а в меню не возвращаемся: состав часто правят
     * несколькими нажатиями подряд.
     */
    private void setAssignee(BotRequest request, Member actor, long memberId) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.EditingTask editing)) {
            expired(request);
            return;
        }

        Task task = tasks.findVisible(actor, editing.taskId());
        boolean onIt =
                task.assignments().stream()
                        .anyMatch(assignment -> assignment.memberId() == memberId);

        // Последнего снять нельзя, а кнопка у него всё равно отмечена — она показывает состав,
        // а не то, что с ним можно сделать. Отвечаем словами: домен здесь бросил бы исключение,
        // и человек увидел бы «что-то пошло не так» на совершенно понятном нажатии.
        if (onIt && task.assignments().size() == 1) {
            edit(
                    request,
                    Texts.LAST_ASSIGNEE_STAYS,
                    TaskEditView.assignees(task, families.roster(actor), editing.kind()));
            return;
        }

        Task updated =
                onIt
                        ? tasks.unassign(actor, editing.taskId(), memberId)
                        : tasks.assign(actor, editing.taskId(), memberId);
        log.info("task {} assignees changed by member {}", updated.id(), actor.id());

        edit(
                request,
                Texts.ASK_ASSIGNEES_EDIT,
                TaskEditView.assignees(updated, families.roster(actor), editing.kind()));
    }

    private void confirmDeletion(BotRequest request, Member actor, TaskRef ref) {
        Task task = editable(actor, ref);
        edit(
                request,
                "Удалить дело безвозвратно?\n\n" + card(task, actor),
                TaskEditView.confirmDeletion(task, ref.kind()));
    }

    /** Жёсткое удаление строки: история закрытых дел живёт в статусах, а удаление — «создал зря». */
    private void delete(BotRequest request, Member actor, TaskRef ref) {
        tasks.delete(actor, ref.taskId());
        dialogs.clear(request.telegramUserId());
        log.info("task {} deleted by member {}", ref.taskId(), actor.id());
        edit(request, "Удалено.", null);
    }

    /**
     * Право на правку проверяется <b>до</b> вопроса: иначе человек напишет новое название и только
     * потом узнает, что менять ему нельзя.
     */
    private Task editable(Member actor, TaskRef ref) {
        Task task = tasks.findVisible(actor, ref.taskId());
        if (!task.mayModify(actor.asActor())) {
            throw new DomainException.NotPermitted("actor may not modify this task");
        }
        return task;
    }

    private void finish(BotRequest request, Member actor, Task task, TaskListView.Kind kind) {
        dialogs.clear(request.telegramUserId());
        sender.send(
                request.chatId(),
                card(task, actor),
                TaskCardView.keyboard(task, actor.asActor(), kind));
    }

    private void expired(BotRequest request) {
        dialogs.clear(request.telegramUserId());
        sender.send(request.chatId(), Texts.DIALOG_EXPIRED);
    }

    private String card(Task task, Member viewer) {
        List<Member> roster = families.roster(viewer);
        Map<Long, Member> byId =
                roster.stream().collect(Collectors.toMap(Member::id, Function.identity()));
        return TaskCardView.render(
                task, byId, families.family(viewer).timezone(), clock.instant());
    }

    private void edit(BotRequest request, String text, InlineKeyboardMarkup markup) {
        request.messageId()
                .ifPresentOrElse(
                        id -> sender.edit(request.chatId(), id, text, markup),
                        () -> sender.send(request.chatId(), text, markup));
    }
}
