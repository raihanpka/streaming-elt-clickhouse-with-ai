// Bromo: Spring Boot application module. Feature modules, see docs/ARCHITECTURE.md.
// `java` is declared explicitly so the Kotlin DSL can resolve `implementation` and
// `testImplementation` at script-compile time, before the Spring Boot plugin applies it.

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.archunit.junit5)
}

springBoot {
    buildInfo()
}

// Regenerates infra/clickhouse init scripts from config/data-contract.yml.
tasks.register<JavaExec>("generateWarehouseSql") {
    group = "bromo"
    description = "Generates the ClickHouse init scripts from config/data-contract.yml"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.bromo.shared.contract.WarehouseSqlMain")
    workingDir = rootProject.projectDir
    args(
        providers.gradleProperty("contractFile").orElse("config/data-contract.yml").get(),
        providers.gradleProperty("outputDir").orElse("infra/clickhouse").get(),
    )
}
