// Bromo: shared library module. Empty, removed in milestone M1 (see docs/DECISIONS.md, ADR 3).

plugins {
    `java-library`
}

dependencies {
    // Intentionally empty. Pure Java types only.
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
