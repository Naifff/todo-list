package com.familytodo.config;

import com.familytodo.application.DueDateParser;
import com.familytodo.application.FamilyService;
import com.familytodo.application.InviteService;
import com.familytodo.application.TaskService;
import com.familytodo.application.port.FamilyRepository;
import com.familytodo.application.port.InviteRepository;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.application.port.Notifier;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.InviteCodeGenerator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Сборка юзкейсов.
 *
 * <p>Классы слоя {@code application} не помечены аннотациями Spring намеренно: слой обязан
 * собираться и тестироваться без контекста. Цена — вот эти явные методы; выгода — ArchUnit-правило
 * «application не зависит от Spring» держится само собой.
 */
@Configuration
public class ApplicationConfig {

    /**
     * Время берётся только отсюда. {@code Instant.now()} в коде означал бы, что поведение на
     * границе суток и на переходе летнего времени проверить нечем.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DueDateParser dueDateParser(Clock clock) {
        return new DueDateParser(clock);
    }

    @Bean
    public InviteCodeGenerator inviteCodeGenerator() {
        return new InviteCodeGenerator();
    }

    @Bean
    public TaskService taskService(
            TaskRepository tasks, MemberRepository members, Notifier notifier, Clock clock) {
        return new TaskService(tasks, members, notifier, clock);
    }

    @Bean
    public FamilyService familyService(
            FamilyRepository families,
            MemberRepository members,
            TaskRepository tasks,
            Notifier notifier,
            Clock clock) {
        return new FamilyService(families, members, tasks, notifier, clock);
    }

    @Bean
    public InviteService inviteService(
            InviteRepository invites,
            MemberRepository members,
            InviteCodeGenerator codes,
            Clock clock) {
        return new InviteService(invites, members, codes, clock);
    }
}
