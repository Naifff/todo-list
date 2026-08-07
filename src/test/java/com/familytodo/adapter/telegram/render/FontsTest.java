package com.familytodo.adapter.telegram.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Font;
import org.junit.jupiter.api.Test;

/**
 * Шрифт берётся из ресурсов, а не из системы.
 *
 * <p>На минимальной VPS шрифтов может не быть вовсе: {@code openjdk-jre-headless} их не тянет. Java
 * в таком случае не падает — она молча подставляет логический шрифт, и кириллица выходит рядами
 * квадратов. Заметить это на макбуке, где шрифтов сотни, невозможно, поэтому проверяем не «текст
 * нарисовался», а что нарисовал его именно наш файл и что в нём есть нужные буквы.
 */
class FontsTest {

    @Test
    void bundledFontIsUsedInsteadOfASystemOne() {
        Font font = Fonts.regular(12f);

        assertThat(font.getFamily()).isEqualTo("DejaVu Sans");
    }

    /** Логические шрифты Java — признак того, что подстановка всё-таки произошла. */
    @Test
    void fontIsNotALogicalFallback() {
        assertThat(Fonts.regular(12f).getFamily())
                .isNotIn("Dialog", "DialogInput", "Serif", "SansSerif", "Monospaced");
    }

    @Test
    void everyCharacterWeDrawHasAGlyph() {
        String drawn =
                "Отвезти детей в школу — «Ока», 08:00–10:00 (Zoom); пн вт ср чт пт сб вс ЁёЙй №"
                        + "0123456789 Daily standup / async-profiler";

        assertThat(Fonts.regular(12f).canDisplayUpTo(drawn))
                .describedAs("индекс первого символа без глифа, -1 если все есть")
                .isEqualTo(-1);
    }

    @Test
    void boldIsDerivedFromTheSameFile() {
        Font bold = Fonts.bold(14f);

        assertThat(bold.getFamily()).isEqualTo("DejaVu Sans");
        assertThat(bold.isBold()).isTrue();
        assertThat(bold.getSize2D()).isEqualTo(14f);
    }

    @Test
    void sizeIsHonoured() {
        assertThat(Fonts.regular(9.5f).getSize2D()).isEqualTo(9.5f);
    }
}
