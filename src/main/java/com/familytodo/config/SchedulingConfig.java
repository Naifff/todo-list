package com.familytodo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Планировщик напоминаний и дайджеста.
 *
 * <p>Отключается свойством {@code scheduling.enabled=false} — иначе любой тест с контекстом начинал
 * бы рассылать напоминания.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    /**
     * Два потока, а не один. На однопоточном пуле медленный {@code ReminderJob} задерживает
     * дайджест: у семьи в другой таймзоне утро наступает по своему расписанию и ждать чужой
     * рассылки не должно.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("jobs-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
