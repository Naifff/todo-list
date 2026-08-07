package com.familytodo.adapter.telegram.render;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * Разбиение названия на строки в пределах блока.
 *
 * <p>Обрезка обязана быть видимой. «Отвезти детей в» читается как законченная фраза, и молчаливое
 * усечение вводит в заблуждение сильнее, чем отсутствие текста.
 *
 * <p>Вынесено из рисующего кода, чтобы проверяться точно: у картинки можно спросить только «не
 * пустая ли она».
 */
final class TextFit {

    private static final String ELLIPSIS = "…";

    private TextFit() {}

    /**
     * @param maxLines сколько строк помещается по высоте; ноль означает, что места нет вовсе
     * @return строки, каждая не шире {@code width}; последняя оканчивается многоточием, если
     *     влезло не всё
     */
    static List<String> lines(String text, FontMetrics metrics, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank() || maxLines <= 0 || width <= 0) {
            return lines;
        }

        String rest = text.strip();
        while (!rest.isEmpty() && lines.size() < maxLines) {
            boolean last = lines.size() == maxLines - 1;
            int taken = fitsUpTo(rest, metrics, width);

            if (taken >= rest.length()) {
                lines.add(rest);
                return lines;
            }
            if (last) {
                lines.add(ellipsise(rest, metrics, width));
                return lines;
            }

            int breakAt = rest.lastIndexOf(' ', taken);
            // слово длиннее строки: переносить не по чему, режем внутри слова
            int cut = breakAt > 0 ? breakAt : Math.max(1, taken);
            lines.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        return lines;
    }

    /** Сколько символов помещается в ширину. */
    private static int fitsUpTo(String text, FontMetrics metrics, int width) {
        int length = 0;
        while (length < text.length() && metrics.stringWidth(text.substring(0, length + 1)) <= width) {
            length++;
        }
        return length;
    }

    /**
     * Многоточие само занимает место, поэтому под него отрезается ещё немного. Пустая строка
     * возможна и допустима: в неё уже не влезает даже один символ с многоточием.
     */
    private static String ellipsise(String text, FontMetrics metrics, int width) {
        int length = fitsUpTo(text, metrics, width);
        while (length > 0 && metrics.stringWidth(text.substring(0, length) + ELLIPSIS) > width) {
            length--;
        }
        return length == 0 ? ELLIPSIS : text.substring(0, length).strip() + ELLIPSIS;
    }
}
