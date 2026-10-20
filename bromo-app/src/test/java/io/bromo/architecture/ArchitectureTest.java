package io.bromo.architecture;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  private static final List<String> SLICES =
      List.of("ingest", "pipeline", "metrics", "findings", "chat");

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.bromo");

  @Test
  void slicesDoNotDependOnEachOther() {
    for (String slice : SLICES) {
      SliceRules.sliceIsIndependent("io.bromo", slice, SLICES).check(PRODUCTION);
    }
  }

  @Test
  void sharedDoesNotDependOnSlices() {
    SliceRules.sharedDoesNotDependOnSlices("io.bromo", SLICES).check(PRODUCTION);
  }

  @Test
  void nothingDependsOnBootstrap() {
    SliceRules.nothingDependsOnBootstrap("io.bromo").check(PRODUCTION);
  }

  @Test
  void ruleFailsWhenOneSliceImportsAnother() {
    JavaClasses fixtures = new ClassFileImporter().importPackages("io.bromo.fixtures");
    List<String> fixtureSlices = List.of("slicea", "sliceb");
    assertThrows(
        AssertionError.class,
        () ->
            SliceRules.sliceIsIndependent("io.bromo.fixtures", "slicea", fixtureSlices)
                .check(fixtures));
  }

  @Test
  void ruleFailsWhenSharedImportsASlice() {
    JavaClasses fixtures = new ClassFileImporter().importPackages("io.bromo.fixtures");
    assertThrows(
        AssertionError.class,
        () ->
            SliceRules.sharedDoesNotDependOnSlices("io.bromo.fixtures", List.of("slicea", "sliceb"))
                .check(fixtures));
  }
}
