package io.bromo.architecture;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  private static final List<String> MODULES =
      List.of("ingest", "pipeline", "metrics", "findings", "chat");

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.bromo");

  @Test
  void modulesDoNotDependOnEachOther() {
    for (String module : MODULES) {
      ModuleRules.moduleIsIndependent("io.bromo", module, MODULES).check(PRODUCTION);
    }
  }

  @Test
  void sharedDoesNotDependOnModules() {
    ModuleRules.sharedDoesNotDependOnModules("io.bromo", MODULES).check(PRODUCTION);
  }

  @Test
  void nothingDependsOnBootstrap() {
    ModuleRules.nothingDependsOnBootstrap("io.bromo").check(PRODUCTION);
  }

  @Test
  void ruleFailsWhenOneModuleImportsAnother() {
    JavaClasses fixtures = new ClassFileImporter().importPackages("io.bromo.fixtures");
    List<String> fixtureModules = List.of("modulea", "moduleb");
    assertThrows(
        AssertionError.class,
        () ->
            ModuleRules.moduleIsIndependent("io.bromo.fixtures", "modulea", fixtureModules)
                .check(fixtures));
  }

  @Test
  void ruleFailsWhenSharedImportsAModule() {
    JavaClasses fixtures = new ClassFileImporter().importPackages("io.bromo.fixtures");
    assertThrows(
        AssertionError.class,
        () ->
            ModuleRules.sharedDoesNotDependOnModules(
                    "io.bromo.fixtures", List.of("modulea", "moduleb"))
                .check(fixtures));
  }
}
