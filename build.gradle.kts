// Bromo: Root build
// Subprojects: bromo-app (Java API, ingest, UI hosting) and bromo-core (empty, removed in milestone M1).
// The `java` plugin is applied per subproject via its own `plugins { }` block
// (`java-library` for bromo-core, transitively via `spring-boot` for bromo-app).
// Shared configuration is registered as a deferred callback that fires once
// each subproject has applied the Java plugin.

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotless) apply false
}

subprojects {
    group = "io.bromo"
    version = "0.1.0-SNAPSHOT"

    plugins.apply("com.diffplug.spotless")

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    plugins.withId("com.diffplug.spotless") {
        extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            java {
                googleJavaFormat("1.35.0")
                removeUnusedImports()
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all",
                "-Xlint:-processing",
                "-parameters",
            ),
        )
    }
}
