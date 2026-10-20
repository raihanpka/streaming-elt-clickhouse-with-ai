package io.bromo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** Dependency rules for feature modules, reusable on real code and on test fixtures. */
final class ModuleRules {

  private ModuleRules() {}

  /** A module must not depend on any other module. */
  static ArchRule moduleIsIndependent(String base, String module, List<String> allModules) {
    String[] others =
        allModules.stream()
            .filter(s -> !s.equals(module))
            .map(s -> base + "." + s + "..")
            .toArray(String[]::new);
    return noClasses()
        .that()
        .resideInAPackage(base + "." + module + "..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(others)
        .because("module " + module + " must not import another module")
        .allowEmptyShould(true);
  }

  /** The shared package must not depend on any module. */
  static ArchRule sharedDoesNotDependOnModules(String base, List<String> modules) {
    String[] all = modules.stream().map(s -> base + "." + s + "..").toArray(String[]::new);
    return noClasses()
        .that()
        .resideInAPackage(base + ".shared..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(all)
        .because("shared must not import a module")
        .allowEmptyShould(true);
  }

  /** Only bootstrap may be referenced by nothing else. */
  static ArchRule nothingDependsOnBootstrap(String base) {
    return noClasses()
        .that()
        .resideOutsideOfPackage(base + ".bootstrap..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(base + ".bootstrap..")
        .because("only bootstrap may know every module")
        .allowEmptyShould(true);
  }
}
