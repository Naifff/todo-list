package com.familytodo.application.fake;

import com.familytodo.application.port.Notifier;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.util.ArrayList;
import java.util.List;

/** Записывает отправленное вместо отправки: уведомления проверяются как факт, а не как текст. */
public final class FakeNotifier implements Notifier {

    public record Sent(Kind kind, long recipientId, long taskId, String detail) {}

    public enum Kind {
        ASSIGNED,
        COMPLETED,
        DECLINED,
        REOPENED,
        CANCELLED,
        UNASSIGNED,
        DUE,
        DIGEST
    }

    private final List<Sent> sent = new ArrayList<>();

    @Override
    public void taskAssigned(Member recipient, Task task) {
        sent.add(new Sent(Kind.ASSIGNED, recipient.id(), task.id(), null));
    }

    @Override
    public void taskCompleted(Member recipient, Task task, Member by) {
        sent.add(new Sent(Kind.COMPLETED, recipient.id(), task.id(), null));
    }

    @Override
    public void taskDeclined(Member recipient, Task task, Member by, String reason) {
        sent.add(new Sent(Kind.DECLINED, recipient.id(), task.id(), reason));
    }

    @Override
    public void taskReopened(Member recipient, Task task, Member by) {
        sent.add(new Sent(Kind.REOPENED, recipient.id(), task.id(), null));
    }

    @Override
    public void taskCancelled(Member recipient, Task task, String reason) {
        sent.add(new Sent(Kind.CANCELLED, recipient.id(), task.id(), reason));
    }

    @Override
    public void taskUnassigned(Member recipient, Task task) {
        sent.add(new Sent(Kind.UNASSIGNED, recipient.id(), task.id(), null));
    }

    @Override
    public void taskDue(Member recipient, Task task) {
        sent.add(new Sent(Kind.DUE, recipient.id(), task.id(), null));
    }

    @Override
    public void digest(
            Member recipient,
            List<Task> tasks,
            List<com.familytodo.domain.Lesson> lessons,
            List<Member> family,
            java.time.ZoneId zone,
            java.time.LocalDate from,
            int horizonDays) {
        sent.add(new Sent(Kind.DIGEST, recipient.id(), tasks.size(), null));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }
}
