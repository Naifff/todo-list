package com.familytodo.adapter.telegram;

import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.adapter.telegram.view.TaskListView;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.Notifier;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Доставка уведомлений в личные чаты.
 *
 * <p>Два обязательства перед вызывающим:
 *
 * <ul>
 *   <li><b>никогда не бросает.</b> Уведомления рассылаются в циклах — по задачам исключённого
 *       участника, по семье в дайджесте, — и падение на одном получателе не должно отменять
 *       остальных;
 *   <li><b>заблокировавшего бота помечает и больше не тревожит.</b> Иначе каждое напоминание годами
 *       уходит в никуда, тратя запрос и место в логе.
 * </ul>
 */
@Component
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final BotSender sender;
    private final MemberRepository members;
    private final Clock clock;

    public TelegramNotifier(BotSender sender, MemberRepository members, Clock clock) {
        this.sender = sender;
        this.members = members;
        this.clock = clock;
    }

    @Override
    public void taskAssigned(Member recipient, Task task) {
        send(recipient, "Тебя просят: " + title(task));
    }

    @Override
    public void taskCompleted(Member recipient, Task task, Member by) {
        send(recipient, name(by) + " сделал(а): " + title(task));
    }

    @Override
    public void taskDeclined(Member recipient, Task task, Member by, String reason) {
        send(
                recipient,
                name(by) + " не сможет: " + title(task) + "\nПричина: " + HtmlEscaper.escape(reason));
    }

    @Override
    public void taskReopened(Member recipient, Task task, Member by) {
        send(recipient, name(by) + " вернул(а) в работу: " + title(task));
    }

    @Override
    public void taskCancelled(Member recipient, Task task, String reason) {
        send(recipient, "Дело закрыто: " + title(task) + "\n" + HtmlEscaper.escape(reason));
    }

    @Override
    public void taskUnassigned(Member recipient, Task task) {
        send(recipient, "С тебя сняли: " + title(task));
    }

    @Override
    public void taskDue(Member recipient, Task task) {
        send(recipient, "Срок подошёл: " + title(task));
    }

    @Override
    public void digest(Member recipient, List<Task> tasks, List<Member> family, ZoneId zone) {
        Map<Long, Member> byId =
                family.stream().collect(Collectors.toMap(Member::id, Function.identity()));
        // тот же список, что и по команде: родителю — вся семья, ребёнку — только своё
        TaskListView.Kind kind =
                recipient.isParent() ? TaskListView.Kind.ALL : TaskListView.Kind.MINE;

        TaskListView.Rendered rendered =
                TaskListView.render(
                        "Доброе утро. Дела на сегодня",
                        tasks,
                        byId,
                        kind,
                        zone,
                        clock.instant());
        send(recipient, rendered.text());
    }

    private void send(Member recipient, String html) {
        if (!recipient.isReachable()) {
            return;
        }
        try {
            if (!sender.send(recipient.privateChatId(), html)) {
                markBlocked(recipient);
            }
        } catch (RuntimeException e) {
            // сбой на одном получателе не должен отменять рассылку остальным
            log.warn("notification to member {} failed", recipient.id(), e);
        }
    }

    private void markBlocked(Member recipient) {
        recipient.markBotBlocked();
        members.save(recipient);
        log.info("member {} blocked the bot", recipient.id());
    }

    private static String title(Task task) {
        return "<b>" + HtmlEscaper.escape(task.title()) + "</b>";
    }

    private static String name(Member member) {
        return HtmlEscaper.escape(member.displayName());
    }
}
