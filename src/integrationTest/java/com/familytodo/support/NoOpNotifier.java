package com.familytodo.support;

import com.familytodo.application.port.Notifier;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.ZoneId;
import java.util.List;

/**
 * Заглушка порта уведомлений: тест переопределяет только то, что проверяет.
 *
 * <p>Заведена после третьего раза, когда добавление метода в порт сломало компиляцию сразу
 * нескольких тестов. Наследование здесь оправдано именно тем, что молчание по умолчанию — это и
 * есть требуемое поведение для всего, что тест не рассматривает.
 */
public class NoOpNotifier implements Notifier {

    @Override
    public void taskAssigned(Member recipient, Task task) {}

    @Override
    public void taskCompleted(Member recipient, Task task, Member by) {}

    @Override
    public void taskDeclined(Member recipient, Task task, Member by, String reason) {}

    @Override
    public void taskReopened(Member recipient, Task task, Member by) {}

    @Override
    public void taskCancelled(Member recipient, Task task, String reason) {}

    @Override
    public void taskUnassigned(Member recipient, Task task) {}

    @Override
    public void taskDue(Member recipient, Task task) {}

    @Override
    public void digest(Member recipient, List<Task> tasks, List<Member> family, ZoneId zone) {}
}
