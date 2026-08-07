plugins {
    java
    `jvm-test-suite`
    alias(libs.plugins.springBoot)
    alias(libs.plugins.dependencyManagement)
}

group = "com.familytodo"
version = "0.0.1-SNAPSHOT"

java {
    // toolchain, а не sourceCompatibility: версия компиляции не должна зависеть
    // от того, на каком JDK запущен сам Gradle
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation(libs.telegrambots.longpolling)
    implementation(libs.telegrambots.client)

    // persistence появится в задаче 7 — раньше она ломает спайк
    // ненастроенным DataSource
}

testing {
    suites {
        val test = getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
            }
        }

        // отдельный source set: task `test` подхватывает все классы своего
        // source set, и интеграционные тесты рядом с юнитами поднимали бы
        // Docker на каждом прогоне
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.testcontainers:postgresql")
            }
            targets.all {
                testTask.configure { shouldRunAfter(test) }
            }
        }
    }
}

// Ловушка: зарегистрированный suite сам к `check` не привязывается. Без этой
// строки `./gradlew check` зелёный, не выполнив ни одного интеграционного теста —
// включая обязательный тест межсемейной изоляции.
tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}
