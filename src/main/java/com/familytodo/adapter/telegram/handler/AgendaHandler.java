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
import com.familytodo.application.SchoolService;
import com.familytodo.application.TaskService;
import com.familytodo.domain.Lesson;
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
    private final SchoolService school;
    private final BotSender sender;
    private final Clock clock;

    public AgendaHandler(
            TaskService tasks,
            FamilyService families,
            SchoolService school,
            BotSender sender,
            Clock clock) {
        this.tasks = tasks;
        this.families = families;
        this.school = school;
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
        show(request, request.requireMember(), DEFAULT_DAYS, 0, false);
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        boolean grid = AgendaView.PAGE.equals(data.action());
        boolean list = AgendaView.LIST.equals(data.action());
        boolean pastList = AgendaView.PAST_LIST.equals(data.action());
        boolean past = AgendaView.PAST.equals(data.action()) || pastList;

        // ⚠️ Новое действие надо вписать сюда, иначе оно молча ничего не делает: нажатие
        // отвечено роутером, в журнале пусто, и кнопка выглядит просто неработающей
        if (!grid && !list && !past && !AgendaView.DAYS.equals(data.action())) {
            return;
        }
        // ⚠️ у переключателя горизонта аргумент несёт ещё и страницу («7p2»), у остальных действий
        // это по-прежнему одно число. Старая кнопка из одного числа открывает первую страницу
        int days = AgendaView.horizonOf(data.argument());
        int page = AgendaView.pageOf(data.argument());
        // горизонт приходит от клиента: чужое число не должно превращаться в запрос на год.
        // У истории свой набор — «за день» назад не предлагается, значит и принимать нечего
        List<Integer> allowed = past ? AgendaView.PAST_HORIZONS : AgendaView.HORIZONS;
        if (!allowed.contains(days)) {
            throw new IllegalArgumentException("unsupported horizon " + days);
        }

        if (past) {
            sendHistory(request, request.requireMember(), days, pastList);
            return;
        }
        if (grid || list) {
            sendPage(request, request.requireMember(), days, list);
            return;
        }
        show(request, request.requireMember(), days, page, true);
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

        // уроки идут той же страницей и вперемешку с делами: школа занимает больше половины дня.
        // ⚠️ Но только свои: календарь родителя семейный, а расписание ребёнка в нём — не «кто
        // когда занят», а тридцать плашек в неделю, среди которых теряется настоящее дело
        List<Lesson> lessons = school.own(viewer);

        byte[] html =
                asList
                        ? CalendarHtmlRenderer.renderList(
                                dated, undated, lessons, byId, zone, today, days, today)
                        : CalendarHtmlRenderer.render(
                                dated, undated, lessons, byId, zone, today, days, today);
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
     * История — то же расписание, но назад, и с тем же выбором формы.
     *
     * <p>Раньше приходила только списком: считалось, что за месяц сетка нечитаема. Это верно про
     * <b>ось часов</b>, но сетка за месяц — не ось, а раскладка неделями: она читается, и просить
     * её человек вправе.
     */
    private void sendHistory(BotRequest request, Member viewer, int days, boolean asList) {
        ZoneId zone = families.family(viewer).timezone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate from = today.minusDays(days);

        Instant since = from.atStartOfDay(zone).toInstant();
        Instant until = today.atStartOfDay(zone).toInstant();

        List<Task> past = tasks.find(TaskQuery.history(viewer, since, until));
        Map<Long, Member> byId =
                families.roster(viewer).stream()
                        .collect(Collectors.toMap(Member::id, Function.identity()));

        // ⚠️ В истории уроков НЕТ, и это решение. Расписание не версионируется: наложив сегодняшнее
        // правило на май, мы показали бы предметы, которых тогда не было, — и отличить это от правды
        // было бы нельзя. История про то, что случилось, а у урока следа не остаётся.
        byte[] html =
                asList
                        ? CalendarHtmlRenderer.renderList(
                                past, List.of(), List.of(), byId, zone, from, days, today)
                        : CalendarHtmlRenderer.render(
                                past, List.of(), List.of(), byId, zone, from, days, today);
        sender.sendDocument(
                request.chatId(),
                html,
                // ⚠️ форма в имени обязательна наравне с горизонтом: под одним именем телефон
                // открывает ранее скачанный файл, и «сеткой» показывается вчерашним списком
                "history-" + (asList ? "list" : "grid") + "-" + days + "d-" + today + ".html",
                AgendaView.historyCaption(days));
    }

    private void show(BotRequest request, Member viewer, int days, int page, boolean rewrite) {
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
                AgendaView.render(dated, undated, byId, zone, now, days, page);
        var keyboard = AgendaView.keyboard(rendered, days);

        if (rewrite && request.messageId().isPresent()) {
            // переключение горизонта переписывает то же сообщение, а не плодит новые
            sender.edit(request.chatId(), request.messageId().get(), rendered.text(), keyboard);
        } else {
            sender.send(request.chatId(), rendered.text(), keyboard);
        }
    }
}
