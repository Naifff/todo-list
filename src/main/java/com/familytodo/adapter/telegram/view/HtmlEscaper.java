package com.familytodo.adapter.telegram.view;

/**
 * Экранирование пользовательского текста для {@code parse_mode=HTML}.
 *
 * <p>HTML выбран против MarkdownV2 из-за объёма: экранировать нужно три символа вместо
 * восемнадцати. Неэкранированный пользовательский текст — это не косметика: Telegram отвечает на
 * него HTTP 400, то есть сообщение просто не доходит.
 */
public final class HtmlEscaper {

    /** Предел длины сообщения в Telegram. */
    public static final int MESSAGE_LIMIT = 4096;

    private static final char ELLIPSIS = '…';

    private HtmlEscaper() {}

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.append(replacement(text.charAt(i)));
        }
        return out.toString();
    }

    /**
     * Экранирует и укладывает результат в {@code maxLength} символов.
     *
     * <p>Обрезать после экранирования нельзя: разрез придётся на середину сущности и получится
     * {@code &am}. Поэтому длина считается по экранированному представлению, а режется исходный
     * текст — посимвольно, пока следующий символ ещё помещается.
     */
    public static String escapeWithin(String text, int maxLength) {
        String escaped = escape(text);
        if (escaped.length() <= maxLength) {
            return escaped;
        }

        int budget = maxLength - 1; // место под многоточие
        StringBuilder out = new StringBuilder(budget + 1);
        for (int i = 0; i < text.length(); i++) {
            String piece = replacement(text.charAt(i));
            if (out.length() + piece.length() > budget) {
                break;
            }
            out.append(piece);
        }
        return out.append(ELLIPSIS).toString();
    }

    public static String escapeWithinMessage(String text) {
        return escapeWithin(text, MESSAGE_LIMIT);
    }

    private static String replacement(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            default -> String.valueOf(c);
        };
    }
}
