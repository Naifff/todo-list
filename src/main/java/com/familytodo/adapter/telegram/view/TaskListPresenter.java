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
        present(viewer, kind, 0, (text, markup) -> sender.send(chatId, text, markup));
    }

    /** Список живёт одним сообщением: возврат переписывает его, а не добавляет ещё одно. */
    public void edit(long chatId, int messageId, Member viewer, TaskListView.Kind kind, int page) {
        present(viewer, kind, page, (text, markup) -> sender.edit(chatId, messageId, text, markup));
    }

    /**
     * Показать список так, чтобы названное дело оказалось на экране.
     *
     * <p>Возврат из карточки обязан вести туда, откуда пришли: человек листал до двадцать третьего
     * дела, и первая страница — не то место, где он был. Номер страницы <b>вычисляется</b> по месту
     * дела в свежем списке, а не носится в кнопке: список за это время мог измениться.
     *
     * <p>Дела в списке уже нет — его только что закрыли — значит и страницы у него нет: тогда
     * первая, других сведений о том, где человек был, у нас не осталось.
     */
    public void editAround(
            long chatId, int messageId, Member viewer, TaskListView.Kind kind, long taskId) {
        List<Task> found = tasks.find(query(viewer, kind));
        int index = indexOf(found, taskId);
        edit(chatId, messageId, viewer, kind, index < 0 ? 0 : index / TaskListView.PAGE_SIZE);
    }

    private static int indexOf(List<Task> tasks, long taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id() == taskId) {
                return i;
            }
        }
        return -1;
    }

    private void present(Member viewer, TaskListView.Kind kind, int page, Output output) {
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
                TaskListView.render(header(kind), found, byId, kind, zone, clock.instant(), page);
        output.write(rendered.text(), TaskListView.keyboard(found, kind, rendered));
    }

    /**
     * ⚠️ Прошедшие события уходят в конец списка — но остаются в нём. Сортировка по моменту ставила
     * их первыми, и вчерашнее выглядело ближайшим; спрятать их целиком оказалось хуже — открыть,
     * перенести на завтра или закрыть стало нечем.
     *
     * <p>Просроченных дел <b>со сроком</b> это не касается: они стоят по сроку и помечены
     * восклицательным знаком.
     */
    private TaskQuery query(Member viewer, TaskListView.Kind kind) {
        TaskQuery query =
                switch (kind) {
                    case MINE -> TaskQuery.assignedTo(viewer);
                    case REQUESTED -> TaskQuery.createdBy(viewer);
                    case ALL -> TaskQuery.visibleTo(viewer);
                };
        return query.pastEventsLast(startOfToday(viewer));
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
