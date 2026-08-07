package com.familytodo.adapter.telegram.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Разбиение названия на строки внутри блока.
 *
 * <p>Вынесено из рисующего кода по той же причине, что и {@link Lanes}: обрезку можно проверить
 * точно, а картинку — только на «непустая». Молча обрезанное название — не косметика: «Отвезти
 * детей в» читается как законченная фраза и вводит в заблуждение.
 */
class TextFitTest {

    private FontMetrics metrics;
    private int emWidth;

    @BeforeEach
    void setUp() {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setFont(Fonts.regular(11f));
        metrics = g.getFontMetrics();
        emWidth = metrics.stringWidth("m");
    }

    @Nested
    class Wrapping {

        @Test
        void shortTextStaysOnOneLine() {
            assertThat(fit("Мусор", 40 * emWidth, 3)).containsExactly("Мусор");
        }

        @Test
        void longTextWrapsOnSpaces() {
            List<String> lines = fit("Отвезти детей в школу", widthOf("Отвезти детей"), 3);

            assertThat(lines).hasSizeGreaterThan(1);
            assertThat(String.join(" ", lines)).isEqualTo("Отвезти детей в школу");
        }

        @Test
        void emptyTextGivesNoLines() {
            assertThat(fit("", 100, 3)).isEmpty();
            assertThat(fit("   ", 100, 3)).isEmpty();
        }

        @Test
        void noRoomForEvenOneLineGivesNothing() {
            assertThat(fit("Отвезти детей", 100, 0)).isEmpty();
        }
    }

    @Nested
    class Truncation {

        /** Главное: обрезанный текст обязан быть видимо обрезанным. */
        @Test
        void textThatDoesNotFitEndsWithAnEllipsis() {
            List<String> lines = fit("Отвезти детей в школу к первому уроку", widthOf("Отвезти детей"), 1);

            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst()).endsWith("…");
        }

        @Test
        void textThatFitsExactlyHasNoEllipsis() {
            List<String> lines = fit("Отвезти детей в школу", widthOf("Отвезти детей в школу"), 1);

            assertThat(lines).containsExactly("Отвезти детей в школу");
        }

        /** Одно слово длиннее строки: перенос по пробелам тут не поможет, режем внутри слова. */
        @Test
        void singleWordLongerThanTheLineIsCutInside() {
            List<String> lines = fit("Ф".repeat(200), widthOf("ФФФФФФ"), 2);

            assertThat(lines).hasSizeLessThanOrEqualTo(2);
            assertThat(lines.getLast()).endsWith("…");
            assertThat(lines).allSatisfy(l -> assertThat(metrics.stringWidth(l)).isLessThanOrEqualTo(widthOf("ФФФФФФ")));
        }

        /** Ширина в один пиксель не должна давать ни бесконечного цикла, ни исключения. */
        @Test
        void absurdlyNarrowWidthTerminates() {
            assertThat(fit("Отвезти детей в школу", 1, 3)).hasSizeLessThanOrEqualTo(3);
        }

        @Test
        void everyLineStaysWithinTheWidth() {
            int width = widthOf("Отвезти детей");
            List<String> lines = fit("Профилирование: async-profiler на проде сегодня вечером", width, 4);

            assertThat(lines).allSatisfy(l -> assertThat(metrics.stringWidth(l)).isLessThanOrEqualTo(width));
        }
    }

    private List<String> fit(String text, int width, int maxLines) {
        return TextFit.lines(text, metrics, width, maxLines);
    }

    private int widthOf(String sample) {
        return metrics.stringWidth(sample);
    }
}
