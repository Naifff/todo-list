package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
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
        if (!AgendaView.DAYS.equals(data.action())) {
            return;
        }
        int days = (int) data.longArgument();
        // горизонт приходит от клиента: чужое число не должно превращаться в запрос на год
        if (!AgendaView.HORIZONS.contains(days)) {
            throw new IllegalArgumentException("unsupported horizon " + days);
        }
        show(request, request.requireMember(), days, true);
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
