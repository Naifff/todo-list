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

    // SQLite: JdbcClient вместо JPA. На типизированной по значению БД без
    // связей и ленивой загрузки Hibernate давал бы только community-диалект
    // как лишнюю зависимость от чужого мейнтейнера.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // именно стартер, а не голый flyway-core: Boot 4 разнёс автоконфигурацию по
    // модулям, и без spring-boot-flyway миграции просто не запускаются — молча
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.xerial:sqlite-jdbc")
}

testing {
    suites {
        val test = getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation(libs.archunit.junit5)
            }
        }

        // отдельный source set: `test` — чистые юниты, `integrationTest` — всё,
        // что трогает реальную БД и файловую систему. С SQLite это уже не про
        // скорость, а про то, чтобы падение миграции нельзя было спутать с
        // падением доменного правила
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-starter-jdbc")
                implementation("org.springframework.boot:spring-boot-starter-flyway")
                runtimeOnly("org.xerial:sqlite-jdbc")
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
