package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.DialogHandler;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.application.DueDateParser;
import com.familytodo.adapter.telegram.keyboard.NewTaskKeyboards;
import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.adapter.telegram.view.SeriesView;
import com.familytodo.application.FamilyService;
import com.familytodo.application.SeriesService;
import com.familytodo.application.TaskService;
import com.familytodo.domain.Member;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskSeries;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Сценарий {@code /new}: текст → исполнитель → срок → повторение.
 *
 * <p>Состояние живёт в памяти 15 минут. Протухший шаг — не ошибка: {@code /new} просто начинается
 * заново, а брошенные диалоги вытесняются сами.
 */
@Component
public class NewTaskHandler implements CommandHandler, CallbackHandler, DialogHandler {

    private static final Logger log = LoggerFactory.getLogger(NewTaskHandler.class);

    private final TaskService tasks;
    private final FamilyService families;
    private final SeriesService seriesService;
    private final DialogStateStore dialogs;
    private final DueDateParser dueDates;
    private final BotSender sender;

    public NewTaskHandler(
            TaskService tasks,
            FamilyService families,
            SeriesService seriesService,
            DialogStateStore dialogs,
            DueDateParser dueDates,
            BotSender sender) {
        this.tasks = tasks;
        this.families = families;
        this.seriesService = seriesService;
        this.dialogs = dialogs;
        this.dueDates = dueDates;
        this.sender = sender;
    }

    @Override
    public Set<String> commands() {
        return Set.of("new");
    }

    @Override
    public String prefix() {
        return NewTaskKeyboards.PREFIX;
    }

    /**
     * Оба интерфейса объявляют этот метод по умолчанию, поэтому Java требует явного выбора. Здесь
     * ответ один и очевидный: просить о деле может только тот, кто уже в семье.
     */
    @Override
    public boolean allowsStrangers() {
        return false;
    }

    @Override
    public void handle(BotRequest request) {
        dialogs.put(request.telegramUserId(), new DialogState.AwaitingTaskTitle());
        sender.send(request.chatId(), Texts.ASK_TASK_TITLE);
    }

    @Override
    public boolean continueDialog(BotRequest request) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        return switch (state) {
            case DialogState.AwaitingTaskTitle ignored -> acceptTitle(request);
            case DialogState.AwaitingCustomDueDate awaiting -> acceptCustomDue(request, awaiting);
            case null, default -> false;
        };
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        switch (data.action()) {
            case NewTaskKeyboards.ASSIGNEE -> chooseAssignee(request, data);
            case NewTaskKeyboards.DUE -> chooseDue(request, data);
            case NewTaskKeyboards.REPEAT -> chooseRepeat(request, data);
            case NewTaskKeyboards.DAY -> toggleDay(request, data);
            default -> log.warn("unknown action {} for prefix n", data.action());
        }
    }

    private boolean acceptTitle(BotRequest request) {
        String title = request.text().trim();
        if (title.isBlank()) {
            sender.send(request.chatId(), Texts.ASK_TASK_TITLE);
            return true;
        }
        if (title.length() > Task.MAX_TITLE_LENGTH) {
            sender.send(request.chatId(), Texts.TASK_TITLE_TOO_LONG);
            return true;
        }

        Member creator = request.requireMember();
        dialogs.put(request.telegramUserId(), new DialogState.AwaitingAssignee(title));
        sender.send(
                request.chatId(),
                Texts.ASK_ASSIGNEE,
                NewTaskKeyboards.assignees(families.roster(creator), creator.id()));
        return true;
    }

    private void chooseAssignee(BotRequest request, CallbackData data) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.AwaitingAssignee awaiting)) {
            expired(request);
            return;
        }

        dialogs.put(
                request.telegramUserId(),
                new DialogState.AwaitingDueDate(awaiting.title(), data.longArgument()));
        sender.send(request.chatId(), Texts.ASK_DUE, NewTaskKeyboards.dueDates());
    }

    private void chooseDue(BotRequest request, CallbackData data) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.AwaitingDueDate awaiting)) {
            expired(request);
            return;
        }

        if (NewTaskKeyboards.CUSTOM.equals(data.argument())) {
            dialogs.put(
                    request.telegramUserId(),
                    new DialogState.AwaitingCustomDueDate(
                            awaiting.title(), awaiting.assigneeId()));
            sender.send(request.chatId(), Texts.ASK_CUSTOM_DUE);
            return;
        }

        ZoneId zone = families.family(request.requireMember()).timezone();
        Instant dueAt =
                switch (data.argument()) {
                    case NewTaskKeyboards.TODAY -> dueDates.today(zone);
                    case NewTaskKeyboards.TOMORROW -> dueDates.tomorrow(zone);
                    case NewTaskKeyboards.WEEKEND -> dueDates.weekend(zone);
                    default -> null; // «Без срока» и всё, чего мы не знаем
                };

        askRepeat(request, awaiting.title(), awaiting.assigneeId(), dueAt);
    }

    private boolean acceptCustomDue(BotRequest request, DialogState.AwaitingCustomDueDate awaiting) {
        ZoneId zone = families.family(request.requireMember()).timezone();
        Optional<Instant> dueAt = dueDates.parse(request.text(), zone);

        if (dueAt.isEmpty()) {
            // состояние не сбрасываем: человек ошибся в формате, а не передумал
            sender.send(request.chatId(), Texts.DUE_NOT_PARSED);
            return true;
        }

        askRepeat(request, awaiting.title(), awaiting.assigneeId(), dueAt.get());
        return true;
    }

    /**
     * У серии обязано быть время, поэтому «Без срока» повторять нечего — такое дело создаётся
     * сразу, без лишнего шага.
     */
    private void askRepeat(BotRequest request, String title, long assigneeId, Instant dueAt) {
        if (dueAt == null) {
            create(request, title, assigneeId, null);
            return;
        }
        dialogs.put(
                request.telegramUserId(), new DialogState.AwaitingRepeat(title, assigneeId, dueAt));
        sender.send(request.chatId(), Texts.ASK_REPEAT, NewTaskKeyboards.repeatOptions());
    }

    private void chooseRepeat(BotRequest request, CallbackData data) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);

        if (NewTaskKeyboards.DAYS_DONE.equals(data.argument())) {
            finishPicker(request, state);
            return;
        }
        if (!(state instanceof DialogState.AwaitingRepeat awaiting)) {
            expired(request);
            return;
        }

        switch (data.argument()) {
            case NewTaskKeyboards.ONCE ->
                    create(request, awaiting.title(), awaiting.assigneeId(), awaiting.dueAt());
            case NewTaskKeyboards.DAILY -> createSeries(request, awaiting, Recurrence.daily());
            case NewTaskKeyboards.WEEKDAYS -> createSeries(request, awaiting, Recurrence.weekdays());
            case NewTaskKeyboards.PICK_DAYS -> openPicker(request, awaiting);
            default -> log.warn("unknown repeat option {}", data.argument());
        }
    }

    private void openPicker(BotRequest request, DialogState.AwaitingRepeat awaiting) {
        dialogs.put(
                request.telegramUserId(),
                new DialogState.ChoosingDays(
                        awaiting.title(), awaiting.assigneeId(), awaiting.dueAt(), Set.of()));
        sender.send(request.chatId(), Texts.ASK_REPEAT, NewTaskKeyboards.dayPicker(Set.of()));
    }

    private void toggleDay(BotRequest request, CallbackData data) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.ChoosingDays choosing)) {
            expired(request);
            return;
        }

        DayOfWeek day = DayOfWeek.of((int) data.longArgument());
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        days.addAll(choosing.days());
        if (!days.remove(day)) {
            days.add(day);
        }

        dialogs.put(
                request.telegramUserId(),
                new DialogState.ChoosingDays(
                        choosing.title(), choosing.assigneeId(), choosing.dueAt(), days));
        sender.send(request.chatId(), Texts.ASK_REPEAT, NewTaskKeyboards.dayPicker(days));
    }

    private void finishPicker(BotRequest request, DialogState state) {
        if (!(state instanceof DialogState.ChoosingDays choosing)) {
            expired(request);
            return;
        }
        if (choosing.days().isEmpty()) {
            // состояние не сбрасываем: человек не передумал, а ещё ничего не отметил
            sender.send(
                    request.chatId(),
                    Texts.PICK_AT_LEAST_ONE_DAY,
                    NewTaskKeyboards.dayPicker(Set.of()));
            return;
        }

        createSeries(
                request,
                new DialogState.AwaitingRepeat(
                        choosing.title(), choosing.assigneeId(), choosing.dueAt()),
                Recurrence.on(choosing.days()));
    }

    /**
     * Время серии — время выбранного срока, а первый день — его дата. Спрашивать это отдельно
     * значило бы добавить два шага ради того, что человек только что назвал.
     */
    private void createSeries(
            BotRequest request, DialogState.AwaitingRepeat awaiting, Recurrence recurrence) {
        Member creator = request.requireMember();
        ZoneId zone = families.family(creator).timezone();
        ZonedDateTime start = awaiting.dueAt().atZone(zone);

        TaskSeries created =
                seriesService.create(
                        creator,
                        awaiting.assigneeId(),
                        awaiting.title(),
                        recurrence,
                        start.toLocalTime(),
                        null,
                        null,
                        start.toLocalDate());
        dialogs.clear(request.telegramUserId());

        sender.send(
                request.chatId(),
                "Записал: <b>"
                        + HtmlEscaper.escape(created.title())
                        + "</b>\n"
                        + SeriesView.describe(created));
    }

    private void create(BotRequest request, String title, long assigneeId, Instant dueAt) {
        Task task = tasks.create(request.requireMember(), assigneeId, title, dueAt);
        dialogs.clear(request.telegramUserId());
        log.info("task {} created in family {}", task.id(), task.familyId());

        sender.send(
                request.chatId(),
                "Записал: <b>" + HtmlEscaper.escape(task.title()) + "</b>");
    }

    private void expired(BotRequest request) {
        dialogs.clear(request.telegramUserId());
        sender.send(request.chatId(), Texts.DIALOG_EXPIRED);
    }
}
