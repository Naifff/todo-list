package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.render.CalendarHtmlRenderer;
import com.familytodo.adapter.telegram.view.AgendaView;
import com.familytodo.application.FamilyService;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.TaskService;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Расписание на 1 / 3 / 7 / 30 дней: {@code /agenda}. */
@Component
public class AgendaHandler implements CommandHandler, CallbackHandler {

    private static final int DEFAULT_DAYS = 1;

    private final TaskService tasks;
    private final FamilyService families;
    private final BotSender sender;
    private final Clock clock;

    public AgendaHandler(
            TaskService tasks, FamilyService families, BotSender sender, Clock clock) {
        this.tasks = tasks;
        this.families = families;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public Set<String> commands() {
        return Set.of("agenda");
    }

    @Override
    public String prefix() {
        return AgendaView.PREFIX;
    }

    @Override
    public boolean allowsStrangers() {
        return false;
    }

    @Override
    public void handle(BotRequest request) {
        show(request, request.requireMember(), DEFAULT_DAYS, false);
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        boolean grid = AgendaView.PAGE.equals(data.action());
        boolean list = AgendaView.LIST.equals(data.action());
        boolean past = AgendaView.PAST.equals(data.action());
        if (!grid && !list && !past && !AgendaView.DAYS.equals(data.action())) {
            return;
        }
        int days = (int) data.longArgument();
        // горизонт приходит от клиента: чужое число не должно превращаться в запрос на год
        List<Integer> allowed = past ? AgendaView.PAST_HORIZONS : AgendaView.HORIZONS;
        if (!allowed.contains(days)) {
            throw new IllegalArgumentException("unsupported horizon " + days);
        }

        if (past) {
            sendHistory(request, request.requireMember(), days);
            return;
        }
        if (grid || list) {
            sendPage(request, request.requireMember(), days, list);
            return;
        }
        show(request, request.requireMember(), days, true);
    }

    /**
     * Страница приходит файлом и новым сообщением — по той же причине, что и картинка: списка с
     * кнопками она не заменяет, иначе человек остался бы без действий над делами.
     *
     * <p>Дела без даты входят в сам файл, поэтому в подписи их не пересчитываем.
     */
    private void sendPage(BotRequest request, Member viewer, int days, boolean asList) {
        ZoneId zone = families.family(viewer).timezone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(days).atStartOfDay(zone).toInstant();

        List<Task> dated = tasks.find(TaskQuery.inRange(viewer, from, to));
        List<Task> undated = tasks.find(TaskQuery.undated(viewer));
        Map<Long, Member> byId =
                families.roster(viewer).stream()
                        .collect(Collectors.toMap(Member::id, Function.identity()));

        byte[] html =
                asList
                        ? CalendarHtmlRenderer.renderList(dated, undated, byId, zone, today, days)
                        : CalendarHtmlRenderer.render(dated, undated, byId, zone, today, days);
        sender.sendDocument(
                request.chatId(),
                html,
                // ⚠️ горизонт в имени обязателен: под одним именем телефон открывает ранее
                // скачанный файл, и неделя показывается вчерашним днём. Сервер при этом отдаёт
                // правильный документ, поэтому проверка содержимого такое не ловит
                "schedule-" + (asList ? "list" : "grid") + "-" + days + "d-" + today + ".html",
                AgendaView.caption(days, 0));
    }

    /**
     * История — списком, а не сеткой: за месяц сетка нечитаема, а вопрос к истории обычно «что
     * было», а не «во сколько».
     */
    private void sendHistory(BotRequest request, Member viewer, int days) {
        ZoneId zone = families.family(viewer).timezone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate from = today.minusDays(days);

        Instant since = from.atStartOfDay(zone).toInstant();
        Instant until = today.atStartOfDay(zone).toInstant();

        List<Task> past = tasks.find(TaskQuery.history(viewer, since, until));
        Map<Long, Member> byId =
                families.roster(viewer).stream()
                        .collect(Collectors.toMap(Member::id, Function.identity()));

        byte[] html = CalendarHtmlRenderer.renderList(past, List.of(), byId, zone, from, days);
        sender.sendDocument(
                request.chatId(),
                html,
                "history-" + days + "d-" + today + ".html",
                AgendaView.historyCaption(days));
    }

    private void show(BotRequest request, Member viewer, int days, boolean rewrite) {
        ZoneId zone = families.family(viewer).timezone();
        Instant now = clock.instant();

        // окно считается по календарю семьи: «три дня» это три её дня, а не 72 часа
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(days).atStartOfDay(zone).toInstant();

        List<Task> dated = tasks.find(TaskQuery.inRange(viewer, from, to));
        List<Task> undated = tasks.find(TaskQuery.undated(viewer));
        Map<Long, Member> byId =
                families.roster(viewer).stream()
                        .collect(Collectors.toMap(Member::id, Function.identity()));

        AgendaView.Rendered rendered =
                AgendaView.render(dated, undated, byId, zone, now, days);
        var keyboard = AgendaView.keyboard(rendered.shown(), days);

        if (rewrite && request.messageId().isPresent()) {
            // переключение горизонта переписывает то же сообщение, а не плодит новые
            sender.edit(request.chatId(), request.messageId().get(), rendered.text(), keyboard);
        } else {
            sender.send(request.chatId(), rendered.text(), keyboard);
        }
    }
}
