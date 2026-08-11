package com.familytodo.adapter.telegram.view;

import com.familytodo.domain.Assignment;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Имена исполнителей одной строкой.
 *
 * <p>Отдельным местом, потому что печатают их три экрана — список, карточка и расписание, — и
 * разъехавшийся формат заметить труднее, чем кажется: «Мама» в одном и «Мама, Папа» в другом
 * выглядят как разные дела.
 *
 * <p>Имя — пользовательский текст, поэтому экранируется здесь же. Забыть экранирование в одном из
 * трёх мест куда легче, чем в одном.
 */
public final class AssigneeNames {

    private AssigneeNames() {}

    public static String of(Task task, Map<Long, Member> byId) {
        return task.assignments().stream()
                .map(assignment -> of(byId, assignment.memberId()))
                .collect(Collectors.joining(", "));
    }

    public static boolean anyoneDeclined(Task task) {
        return task.assignments().stream().anyMatch(Assignment::hasDeclined);
    }

    public static String of(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        return HtmlEscaper.escape(member == null ? "кто-то" : member.displayName());
    }
}
