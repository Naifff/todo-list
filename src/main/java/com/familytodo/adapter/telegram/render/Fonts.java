package com.familytodo.adapter.telegram.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Шрифт для картинок — из ресурсов приложения, не из системы.
 *
 * <p>На минимальной VPS шрифтов может не быть ни одного: пакет JRE их не тянет. Java при этом не
 * падает, а подставляет логический шрифт, и кириллица превращается в ряды квадратов — отказ,
 * который на машине разработчика воспроизвести нельзя.
 *
 * <p>DejaVu Sans 2.34 под лицензией Bitstream Vera, см. {@code fonts/LICENSE.txt}.
 */
public final class Fonts {

    private static final String RESOURCE = "/fonts/DejaVuSans.ttf";

    /**
     * Загружается один раз: {@code createFont} каждый раз разбирает файл заново и создаёт временный
     * ресурс шрифта — на каждой отрисовке это лишние сотни миллисекунд.
     */
    private static final Font BASE = load();

    private Fonts() {}

    public static Font regular(float size) {
        return BASE.deriveFont(size);
    }

    /** Начертание синтезируется: отдельный файл Bold ради заголовков не окупает 700 килобайт. */
    public static Font bold(float size) {
        return BASE.deriveFont(Font.BOLD, size);
    }

    private static Font load() {
        try (InputStream in = Fonts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("шрифт не найден в ресурсах: " + RESOURCE);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            // молчать нельзя: без своего шрифта картинка соберётся, но окажется нечитаемой
            throw new IllegalStateException("не удалось загрузить шрифт " + RESOURCE, e);
        }
    }
}
