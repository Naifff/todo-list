package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.domain.Lesson;
import com.familytodo.domain.Member;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Экран расписания.
 *
 * <p>⚠️ Расписание показывается <b>в том же формате, которым вводится</b>. Это не украшение: правка
 * тогда делается копированием — скопировал сообщение, поправил строку, послал обратно. Иначе
 * человеку пришлось бы набирать тридцать уроков заново из-за одной замены.
 */
public final class SchoolView {

    public static final String PREFIX = "sc";

    public static final String OPEN = "o";

    public static final String REPLACE = "r";

    public static final String CLEAR = "c";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private SchoolView() {}

    public static String render(Member pupil, List<Lesson> lessons) {
        StringBuilder out =
                new StringBuilder("🎒 <b>Расписание: ")
                        .append(HtmlEscaper.escape(pupil.displayName()))
                        .append("</b>");
        if (lessons.isEmpty()) {
            return out.append("\n\n").append(Texts.SCHOOL_EMPTY).toString();
        }

        out.append("\n\n<code>");
        for (Lesson lesson : lessons) {
            out.append(DayNames.shortName(lesson.day()))
                    .append(' ')
                    .append(lesson.startsAt().format(TIME))
                    .append(' ')
                    .append(HtmlEscaper.escape(lesson.subject()))
                    .append('\n');
        }
        out.append("</code>");

        if (lessons.getFirst().validTo() != null) {
            out.append("\nДо ").append(lessons.getFirst().validTo().format(DATE));
        }
        return out.toString();
    }

    public static InlineKeyboardMarkup keyboard(Member pupil, List<Lesson> lessons) {
        InlineKeyboardRow actions =
                new InlineKeyboardRow(button(Texts.SCHOOL_REPLACE, REPLACE, pupil.id()));
        // ⚠️ «Очистить» появляется, только когда есть что чистить: кнопка, которая ничего не делает,
        // читается как сломанная — ответить «нечего чистить» отсюда нечем
        if (!lessons.isEmpty()) {
            actions.add(button(Texts.SCHOOL_CLEAR, CLEAR, pupil.id()));
        }
        return InlineKeyboardMarkup.builder().keyboardRow(actions).build();
    }

    /** Выбор школьника. Родителю без него не обойтись: расписаний столько же, сколько детей. */
    public static InlineKeyboardMarkup pupils(List<Member> candidates) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (Member candidate : candidates) {
            row.add(button(candidate.displayName(), OPEN, candidate.id()));
            if (row.size() == 2) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardButton button(String label, String action, long memberId) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(CallbackData.of(PREFIX, action, memberId).serialize())
                .build();
    }
}
