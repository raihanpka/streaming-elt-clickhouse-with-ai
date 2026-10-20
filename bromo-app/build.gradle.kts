// Bromo: Spring Boot application module. Vertical slices, see docs/ARCHITECTURE.md.
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
