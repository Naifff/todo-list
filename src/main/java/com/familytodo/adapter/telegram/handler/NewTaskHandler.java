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
import com.familytodo.adapter.telegram.view.AssigneeNames;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    /**
     * {@code /new} без аргумента спрашивает название; с аргументом — принимает дело целиком, ровно
     * как если бы его написали следующим сообщением.
     */
    @Override
    public void handle(BotRequest request) {
        Optional<String> written = request.commandArgument();
        if (written.isPresent()) {
            accept(request, written.get());
            return;
        }
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
            case NewTaskKeyboards.TOGGLE_ASSIGNEE -> toggleAssignee(request, data);
            case NewTaskKeyboards.ASSIGNEES_DONE -> finishAssigneePicker(request);
            case NewTaskKeyboards.DUE -> chooseDue(request, data);
            case NewTaskKeyboards.REPEAT -> chooseRepeat(request, data);
            case NewTaskKeyboards.DAY -> toggleDay(request, data);
            default -> log.warn("unknown action {} for prefix n", data.action());
        }
    }

    private boolean acceptTitle(BotRequest request) {
        return accept(request, request.text());
    }

    /**
     * Принять написанное: либо просто название, либо дело целиком — «сходить на ролики 14.08
     * 18:30-20:00 цирк».
     *
     * <p>Разбор пробуется всегда и молча: не нашлось даты — значит это название, дальше как раньше.
     */
    private boolean accept(BotRequest request, String written) {
        String text = written == null ? "" : written.trim();
        if (text.isBlank()) {
            sender.send(request.chatId(), Texts.ASK_TASK_TITLE);
            return true;
        }

        Member creator = request.requireMember();
        ZoneId zone = families.family(creator).timezone();
        Optional<DueDateParser.Titled> titled = dueDates.parseTitled(text, zone);

        String title = titled.map(DueDateParser.Titled::title).orElse(text);
        DueDateParser.Plan plan = titled.map(DueDateParser.Titled::plan).orElse(null);

        if (title.length() > Task.MAX_TITLE_LENGTH) {
            sender.send(request.chatId(), Texts.TASK_TITLE_TOO_LONG);
            return true;
        }

        dialogs.put(
                request.telegramUserId(),
                new DialogState.ChoosingAssignees(title, List.of(), plan));
        sender.send(
                request.chatId(),
                Texts.ASK_ASSIGNEES,
                NewTaskKeyboards.assignees(
                        families.roster(creator), creator.id(), List.of()));
        return true;
    }

    private void toggleAssignee(BotRequest request, CallbackData data) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.ChoosingAssignees choosing)) {
            expired(request);
            return;
        }

        List<Long> chosen = new ArrayList<>(choosing.chosen());
        if (!chosen.remove(data.longArgument())) {
            chosen.add(data.longArgument());
        }

        Member creator = request.requireMember();
        dialogs.put(
                request.telegramUserId(),
                new DialogState.ChoosingAssignees(choosing.title(), chosen, choosing.plan()));

        // перерисовываем на месте, а не новым сообщением: выбор исполнителя теперь на каждом
        // деле, и сообщение на каждое нажатие превратило бы создание дела в ленту из пяти штук
        redraw(
                request,
                Texts.ASK_ASSIGNEES,
                NewTaskKeyboards.assignees(
                        families.roster(creator), creator.id(), chosen));
    }

    /** Правка исходного сообщения; если его нет (так бывает у старых кнопок) — новым. */
    private void redraw(BotRequest request, String text, InlineKeyboardMarkup markup) {
        request.messageId()
                .ifPresentOrElse(
                        id -> sender.edit(request.chatId(), id, text, markup),
                        () -> sender.send(request.chatId(), text, markup));
    }

    private void finishAssigneePicker(BotRequest request) {
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.ChoosingAssignees choosing)) {
            expired(request);
            return;
        }
        if (choosing.chosen().isEmpty()) {
            // состояние не сбрасываем: человек не передумал, а ещё никого не отметил
            Member creator = request.requireMember();
            redraw(
                    request,
                    Texts.PICK_AT_LEAST_ONE_ASSIGNEE,
                    NewTaskKeyboards.assignees(
                            families.roster(creator), creator.id(), List.of()));
            return;
        }

        // срок уже назван строкой — второй вопрос «когда?» подряд был бы лишним нажатием
        if (choosing.plan() != null) {
            askRepeat(request, choosing.title(), choosing.chosen(), choosing.plan());
            return;
        }
        askDue(request, choosing.title(), choosing.chosen());
    }

    private void askDue(BotRequest request, String title, List<Long> assigneeIds) {
        dialogs.put(
                request.telegramUserId(), new DialogState.AwaitingDueDate(title, assigneeIds));
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
                            awaiting.title(), awaiting.assigneeIds()));
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

        askRepeat(
                request,
                awaiting.title(),
                awaiting.assigneeIds(),
                new DueDateParser.Plan(dueAt, null, null, null));
    }

    /**
     * Шаг «Время и место»: дата, время или интервал и место — одной строкой.
     *
     * <p>Заменил прежнюю «Свою дату» и понимает всё, что понимала она. Отдельные шаги для времени и
     * длительности стоили бы двух лишних нажатий на каждом деле, включая те, где интервала нет.
     */
    private boolean acceptCustomDue(BotRequest request, DialogState.AwaitingCustomDueDate awaiting) {
        ZoneId zone = families.family(request.requireMember()).timezone();
        Optional<DueDateParser.Plan> plan = dueDates.parsePlan(request.text(), zone);

        if (plan.isEmpty()) {
            // состояние не сбрасываем: человек ошибся в формате, а не передумал
            sender.send(request.chatId(), Texts.SLOT_NOT_PARSED);
            return true;
        }

        askRepeat(request, awaiting.title(), awaiting.assigneeIds(), plan.get());
        return true;
    }

    /**
     * У серии обязано быть время, поэтому «Без срока» повторять нечего — такое дело создаётся
     * сразу, без лишнего шага.
     */
    private void askRepeat(
            BotRequest request, String title, List<Long> assigneeIds, DueDateParser.Plan plan) {
        if (moment(plan) == null) {
            // ни срока, ни интервала — повторять нечего
            create(request, title, assigneeIds, plan, null);
            return;
        }
        dialogs.put(
                request.telegramUserId(),
                new DialogState.AwaitingRepeat(title, assigneeIds, plan));
        sender.send(request.chatId(), Texts.ASK_REPEAT, NewTaskKeyboards.repeatOptions());
    }

    /** Момент дела: начало интервала, а если его нет — срок. Он же становится временем серии. */
    private static Instant moment(DueDateParser.Plan plan) {
        return plan.startsAt() != null ? plan.startsAt() : plan.dueAt();
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
                    create(request, awaiting.title(), awaiting.assigneeIds(), awaiting.plan(), null);
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
                        awaiting.title(), awaiting.assigneeIds(), awaiting.plan(), Set.of()));
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
                        choosing.title(), choosing.assigneeIds(), choosing.plan(), days));
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
                        choosing.title(), choosing.assigneeIds(), choosing.plan()),
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
        DueDateParser.Plan plan = awaiting.plan();
        ZonedDateTime start = moment(plan).atZone(zone);

        // длительность и место переносятся в серию: иначе повторяющееся «отвезти детей
        // 08:00-08:40 школа» теряло бы всё, кроме времени начала
        Duration duration =
                plan.startsAt() != null && plan.endsAt() != null
                        ? Duration.between(plan.startsAt(), plan.endsAt())
                        : null;

        TaskSeries created =
                seriesService.create(
                        creator,
                        awaiting.assigneeIds(),
                        awaiting.title(),
                        recurrence,
                        start.toLocalTime(),
                        duration,
                        plan.location(),
                        start.toLocalDate());
        dialogs.clear(request.telegramUserId());

        sender.send(
                request.chatId(),
                "Записал: <b>"
                        + HtmlEscaper.escape(created.title())
                        + "</b>\n"
                        + SeriesView.describe(created));
    }

    private void create(
            BotRequest request,
            String title,
            List<Long> assigneeIds,
            DueDateParser.Plan plan,
            String note) {
        Member creator = request.requireMember();
        Task task = tasks.create(creator, assigneeIds, title, plan.dueAt());

        if (plan.startsAt() != null || plan.location() != null) {
            // интервал и место живут в самой задаче, а не в сроке
            task = tasks.schedule(creator, task.id(), plan.startsAt(), plan.endsAt(), plan.location());
        }

        dialogs.clear(request.telegramUserId());
        log.info("task {} created in family {}", task.id(), task.familyId());

        StringBuilder out =
                new StringBuilder("Записал: <b>" + HtmlEscaper.escape(task.title()) + "</b>");
        // кого поручили, повторяем только при нескольких: при одном человек это и так помнит,
        // а лишняя строка в подтверждении на каждое дело — шум
        if (task.assignments().size() > 1) {
            out.append("\nДелают: ")
                    .append(
                            AssigneeNames.of(
                                    task,
                                    families.roster(creator).stream()
                                            .collect(
                                                    Collectors.toMap(
                                                            Member::id, Function.identity()))));
        }
        if (note != null) {
            out.append('\n').append(note);
        }
        sender.send(request.chatId(), out.toString());
    }

    private void expired(BotRequest request) {
        dialogs.clear(request.telegramUserId());
        sender.send(request.chatId(), Texts.DIALOG_EXPIRED);
    }
}
