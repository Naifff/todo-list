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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Показ расписания сообщением — общий для {@code /agenda} и для кнопки «← Назад» из карточки.
 *
 * <p>Вынесено по той же причине, что и {@link TaskListPresenter}: возврат из карточки не должен
 * собирать экран по своим правилам. Здесь это критичнее, чем в списках, — страница расписания
 * считается по месту дела в <b>том же</b> порядке, в котором его рисует вёрстка: сначала
 * датированные, потом дела без срока. Разойдись эти два порядка, «← Назад» открывал бы соседнюю
 * страницу, и объяснить это по экрану было бы нечем.
 */
@Component
public class AgendaPresenter {

    private final TaskService tasks;
    private final FamilyService families;
    private final BotSender sender;
    private final Clock clock;

    public AgendaPresenter(
            TaskService tasks, FamilyService families, BotSender sender, Clock clock) {
        this.tasks = tasks;
        this.families = families;
        this.sender = sender;
        this.clock = clock;
    }

    public void send(long chatId, Member viewer, int days, int page) {
        present(viewer, days, page, (text, markup) -> sender.send(chatId, text, markup));
    }

    public void edit(long chatId, int messageId, Member viewer, int days, int page) {
        present(viewer, days, page, (text, markup) -> sender.edit(chatId, messageId, text, markup));
    }

    /**
     * Показать расписание так, чтобы названное дело оказалось на экране.
     *
     * <p>Номер страницы <b>вычисляется</b> по месту дела в свежей выборке, а не носится в кнопке:
     * за время, пока человек смотрел карточку, расписание могло измениться. Дела в нём уже нет —
     * открывается первая страница: других сведений о том, где человек был, у нас не осталось.
     */
    public void editAround(long chatId, int messageId, Member viewer, int days, long taskId) {
        List<Task> all = all(viewer, days);
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id() == taskId) {
                index = i;
                break;
            }
        }
        edit(chatId, messageId, viewer, days, index < 0 ? 0 : index / TaskListView.PAGE_SIZE);
    }

    private void present(Member viewer, int days, int page, Output output) {
        ZoneId zone = families.family(viewer).timezone();
        Instant now = clock.instant();
        Map<Long, Member> byId =
                families.roster(viewer).stream()
                        .collect(Collectors.toMap(Member::id, Function.identity()));

        AgendaView.Rendered rendered =
                AgendaView.render(dated(viewer, days), undated(viewer), byId, zone, now, days, page);
        output.write(rendered.text(), AgendaView.keyboard(rendered, days));
    }

    /** Тот же порядок, в котором дела попадают на страницы: сначала датированные, потом без срока. */
    private List<Task> all(Member viewer, int days) {
        List<Task> all = new ArrayList<>(dated(viewer, days));
        all.addAll(undated(viewer));
        return all;
    }

    private List<Task> dated(Member viewer, int days) {
        ZoneId zone = families.family(viewer).timezone();
        // окно считается по календарю семьи: «три дня» это три её дня, а не 72 часа
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        return tasks.find(
                TaskQuery.inRange(
                        viewer,
                        today.atStartOfDay(zone).toInstant(),
                        today.plusDays(days).atStartOfDay(zone).toInstant()));
    }

    private List<Task> undated(Member viewer) {
        return tasks.find(TaskQuery.undated(viewer));
    }

    @FunctionalInterface
    private interface Output {
        void write(
                String text,
                org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
                        markup);
    }
}
