package com.familytodo.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Приложение не слушает ни одного порта.
 *
 * <p>Это не настройка, а обязательство: на VPS живёт чужой VPN, и открытый порт был бы вмешательством
 * в сеть машины, которую нельзя ронять. {@code spring.main.web-application-type=none} такой
 * гарантии не даёт — свойство правится одной строкой в чужом профиле и молча.
 *
 * <p>Проверяем поэтому не свойство, а classpath: без сервлетного API и без веб-модулей Spring
 * поднять слушающий сокет нечем, какими бы ни были настройки. Classpath тестов шире продового, так
 * что отсутствие класса здесь означает его отсутствие и в проде.
 *
 * <p>Из этого же следует, что health-эндпоинта нет: признаком живости служит строка {@code long
 * polling started} в журнале. Порт не открывается, а значит опрашивать нечего.
 */
class NoWebServerTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jakarta.servlet.Servlet",
                "org.springframework.web.context.WebApplicationContext",
                "org.springframework.boot.web.server.WebServer"
            })
    void webStackIsAbsentFromTheClasspath(String className) {
        assertThatThrownBy(() -> Class.forName(className))
                .describedAs("appeared on the classpath: %s", className)
                .isInstanceOf(ClassNotFoundException.class);
    }

    /** Проверка самой проверки: класс, который заведомо есть, должен находиться. */
    @Test
    void theCheckItselfCanFindAClassThatIsPresent() throws Exception {
        Class.forName("org.springframework.context.ApplicationContext");
    }
}
