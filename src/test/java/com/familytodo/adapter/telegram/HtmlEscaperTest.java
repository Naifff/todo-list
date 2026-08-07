package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.familytodo.adapter.telegram.view.HtmlEscaper;
import org.junit.jupiter.api.Test;

class HtmlEscaperTest {

    /**
     * Ровно тот случай, ради которого выбран HTML: три опасных символа против восемнадцати в
     * MarkdownV2. Неэкранированный текст — это HTTP 400 и несостоявшаяся доставка, а не кривая
     * разметка.
     */
    @Test
    void escapesEveryDangerousCharacter() {
        String escaped = HtmlEscaper.escape("Купить *хлеб* <срочно> & молоко");

        assertThat(escaped).isEqualTo("Купить *хлеб* &lt;срочно&gt; &amp; молоко");
    }

    /** Амперсанд обрабатывается первым, иначе экранирование само себя перезапишет. */
    @Test
    void doesNotDoubleEscape() {
        assertThat(HtmlEscaper.escape("&lt;")).isEqualTo("&amp;lt;");
    }

    @Test
    void leavesMarkdownCharactersAlone() {
        assertThat(HtmlEscaper.escape("_курсив_ *жирный* `код` [ссылка]"))
                .isEqualTo("_курсив_ *жирный* `код` [ссылка]");
    }

    @Test
    void treatsNullAsEmpty() {
        assertThat(HtmlEscaper.escape(null)).isEmpty();
    }

    @Test
    void keepsShortTextUntouchedAndWithoutEllipsis() {
        assertThat(HtmlEscaper.escapeWithin("Вынести мусор", 100)).isEqualTo("Вынести мусор");
    }

    @Test
    void truncatesLongTextWithEllipsis() {
        String result = HtmlEscaper.escapeWithin("я".repeat(50), 10);

        assertThat(result).hasSize(10).endsWith("…").startsWith("яяя");
    }

    /**
     * Считать длину надо по экранированному тексту, а резать — исходный. Иначе разрез придётся на
     * середину сущности и получится {@code &am}.
     */
    @Test
    void neverCutsAnEntityInHalf() {
        String result = HtmlEscaper.escapeWithin("<".repeat(20), 12);

        assertThat(result).hasSizeLessThanOrEqualTo(12);
        assertThat(result).isEqualTo("&lt;&lt;…");
        // обрыв выглядел бы как "&lt;&l…" — сущность без закрывающей точки с запятой
        assertThat(result).doesNotMatch(".*&[a-z]*…$");
    }

    @Test
    void fitsIntoTelegramMessageLimit() {
        String result = HtmlEscaper.escapeWithinMessage("<".repeat(5000));

        assertThat(result).hasSizeLessThanOrEqualTo(HtmlEscaper.MESSAGE_LIMIT);
        assertThat(result).endsWith("…");
    }
}
