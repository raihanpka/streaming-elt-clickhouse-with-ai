package io.bromo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** Dependency rules for vertical slices, reusable on real code and on test fixtures. */
final class SliceRules {

  private SliceRules() {}

  /** A slice must not depend on any other slice. */
  static ArchRule sliceIsIndependent(String base, String slice, List<String> allSlices) {
    String[] others =
        allSlices.stream()
            .filter(s -> !s.equals(slice))
            .map(s -> base + "." + s + "..")
            .toArray(String[]::new);
    return noClasses()
        .that()
        .resideInAPackage(base + "." + slice + "..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(others)
        .because("slice " + slice + " must not import another slice")
        .allowEmptyShould(true);
  }

  /** The shared package must not depend on any slice. */
  static ArchRule sharedDoesNotDependOnSlices(String base, List<String> slices) {
    String[] all = slices.stream().map(s -> base + "." + s + "..").toArray(String[]::new);
    return noClasses()
        .that()
        .resideInAPackage(base + ".shared..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(all)
        .because("shared must not import a slice")
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
        .because("only bootstrap may know every slice")
        .allowEmptyShould(true);
  }
}
