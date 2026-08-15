package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.BotSender;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Показ списка — общий для команды и для кнопки «← Назад».
 *
 * <p>Вынесено сюда, чтобы возврат из карточки не собирал список по своим правилам: разъехавшиеся
 * заголовки и разный набор задач в двух путях к одному экрану — типовой способ запутать человека.
 */
@Component
public class TaskListPresenter {

    private final TaskService tasks;
    private final FamilyService families;
    private final BotSender sender;
    private final Clock clock;

    public TaskListPresenter(
            TaskService tasks, FamilyService families, BotSender sender, Clock clock) {
        this.tasks = tasks;
        this.families = families;
        this.sender = sender;
        this.clock = clock;
    }

    public void send(long chatId, Member viewer, TaskListView.Kind kind) {
        present(viewer, kind, (text, markup) -> sender.send(chatId, text, markup));
    }

    /** Список живёт одним сообщением: возврат переписывает его, а не добавляет ещё одно. */
    public void edit(long chatId, int messageId, Member viewer, TaskListView.Kind kind) {
        present(viewer, kind, (text, markup) -> sender.edit(chatId, messageId, text, markup));
    }

    private void present(Member viewer, TaskListView.Kind kind, Output output) {
        List<Task> found = tasks.find(query(viewer, kind));
        if (found.isEmpty()) {
            output.write(empty(kind), null);
            return;
        }

        ZoneId zone = families.family(viewer).timezone();
        Map<Long, Member> byId =
                families.roster(viewer).stream()
                        .collect(Collectors.toMap(Member::id, Function.identity()));

        TaskListView.Rendered rendered =
                TaskListView.render(header(kind), found, byId, kind, zone, clock.instant());
        output.write(
                rendered.text(), TaskListView.keyboard(found, kind, rendered.shown()));
    }

    /**
     * ⚠️ Все три списка отсекают прошедшие события — то же правило, что у дайджеста: сделать их уже
     * нельзя. Вчерашние ролики висели открытыми, и сортировка по моменту ставила их первыми, то есть
     * прошедшее выглядело как ближайшее.
     *
     * <p>Просроченных дел <b>со сроком</b> это не касается: их всё ещё можно сделать, они остаются с
     * восклицательным знаком.
     */
    private TaskQuery query(Member viewer, TaskListView.Kind kind) {
        TaskQuery query =
                switch (kind) {
                    case MINE -> TaskQuery.assignedTo(viewer);
                    case REQUESTED -> TaskQuery.createdBy(viewer);
                    case ALL -> TaskQuery.visibleTo(viewer);
                };
        return query.withoutPastEvents(startOfToday(viewer));
    }

    /** Граница по дню, а не по моменту: сегодняшнее событие видно весь день, как и в дайджесте. */
    private Instant startOfToday(Member viewer) {
        ZoneId zone = families.family(viewer).timezone();
        return LocalDate.ofInstant(clock.instant(), zone).atStartOfDay(zone).toInstant();
    }

    private static String header(TaskListView.Kind kind) {
        return switch (kind) {
            case MINE -> Texts.MINE_HEADER;
            case REQUESTED -> Texts.REQUESTED_HEADER;
            case ALL -> Texts.ALL_HEADER;
        };
    }

    private static String empty(TaskListView.Kind kind) {
        return switch (kind) {
            case MINE -> Texts.MINE_EMPTY;
            case REQUESTED -> Texts.REQUESTED_EMPTY;
            case ALL -> Texts.ALL_EMPTY;
        };
    }

    @FunctionalInterface
    private interface Output {
        void write(
                String text,
                org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
                        markup);
    }
}
