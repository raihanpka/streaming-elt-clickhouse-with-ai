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

// Tests read the real data contract so they follow whatever the user put in it.
tasks.test {
    systemProperty("bromo.contract.path", rootProject.file("config/data-contract.yml").absolutePath)
}

// Run the app from the repository root so config/data-contract.yml is found.
tasks.bootRun {
    workingDir = rootProject.projectDir
}
