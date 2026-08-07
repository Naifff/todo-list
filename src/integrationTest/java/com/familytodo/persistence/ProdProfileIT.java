package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Продовый профиль разбирается и даёт собираемый контекст.
 *
 * <p>Опечатка в {@code application-prod.yml} видна только на сервере: локальные запуски идут без
 * профиля, а деплой — это перезапуск службы, после которого разбираться придётся по journald на
 * машине с чужим VPN. Дешевле проверить здесь.
 *
 * <p>Путь к базе переопределён: настоящий указывает в {@code /var/lib/family-todo}, куда тест
 * писать не должен. Проверяется именно разбор профиля и сборка бинов, а не значение пути.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=prod",
            "telegram.bot.token=1:prod-profile-test",
            "telegram.bot.username=prod_profile_bot",
            "telegram.bot.polling.enabled=false",
            "spring.datasource.url=jdbc:sqlite::memory:?foreign_keys=true"
        })
class ProdProfileIT {

    @Autowired private ApplicationContext context;

    @Test
    void contextStartsWithTheProductionProfile() {
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
    }
}
