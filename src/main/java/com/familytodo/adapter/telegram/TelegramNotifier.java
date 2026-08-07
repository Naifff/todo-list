package com.familytodo.adapter.telegram;

import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.application.port.Notifier;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import org.springframework.stereotype.Component;

/**
 * Доставка уведомлений в личные чаты.
 *
 * <p>Пока это базовая версия: карточка задачи с кнопками появится в задаче 16, обработка блокировки
 * бота (HTTP 403) — в задаче 18. Реализация вынесена сюда раньше плана по простой причине: без бина
 * {@link Notifier} приложение не собирается вовсе.
 *
 * <p>Кого уведомлять, решает юзкейс. Здесь — последняя проверка достижимости: исключённым и
 * заблокировавшим бота писать некуда.
 */
@Component
public class TelegramNotifier implements Notifier {

    private final BotSender sender;

    public TelegramNotifier(BotSender sender) {
        this.sender = sender;
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
                name(by)
                        + " не сможет: "
                        + title(task)
                        + "\nПричина: "
                        + HtmlEscaper.escape(reason));
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

    private void send(Member recipient, String html) {
        if (!recipient.isReachable()) {
            return;
        }
        sender.send(recipient.privateChatId(), html);
    }

    private static String title(Task task) {
        return "<b>" + HtmlEscaper.escape(task.title()) + "</b>";
    }

    private static String name(Member member) {
        return HtmlEscaper.escape(member.displayName());
    }
}
