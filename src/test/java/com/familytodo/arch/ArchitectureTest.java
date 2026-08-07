package com.familytodo.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Барьеры, а не проверки текущего кода: эти тесты должны падать от будущих ошибок.
 *
 * <p>Смысл в том, чтобы граница слоёв держалась структурой, а не памятью того, кто правит код
 * через полгода.
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.familytodo");

    /** Домен обязан тестироваться без поднятия контекста — а значит, не знать про Spring. */
    @Test
    void domainDoesNotDependOnSpring() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..")
                .check(CLASSES);
    }

    @Test
    void domainDoesNotDependOnPersistenceTechnology() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "org.flywaydb..")
                .check(CLASSES);
    }

    @Test
    void domainDoesNotDependOnOuterLayers() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..application..", "..config..")
                .check(CLASSES);
    }

    /** Юзкейсы знают порты и домен, но не то, чем порты реализованы. */
    @Test
    void applicationDoesNotDependOnAdapters() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..config..")
                .check(CLASSES);
    }

    /**
     * Бот ходит в хранилище только через юзкейсы. Иначе правила прав, живущие в домене, окажутся
     * в обход — хендлер прочитает задачу сам и покажет то, чего показывать нельзя.
     *
     * <p>С задачи 11 пакет непустой, поэтому {@code allowEmptyShould} здесь снят: правило проверяет
     * реальные классы, а не проходит вхолостую.
     */
    @Test
    void telegramAdapterDoesNotReachPersistenceDirectly() {
        noClasses()
                .that()
                .resideInAPackage("..adapter.telegram..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter.persistence..")
                .check(CLASSES);
    }

    @Test
    void schedulerDoesNotReachPersistenceDirectly() {
        noClasses()
                .that()
                .resideInAPackage("..adapter.scheduler..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter.persistence..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
