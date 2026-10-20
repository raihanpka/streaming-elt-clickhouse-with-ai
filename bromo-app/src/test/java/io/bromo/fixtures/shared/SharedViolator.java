package io.bromo.fixtures.shared;

import io.bromo.fixtures.modulea.Violator;

/** Fixture class in shared that deliberately depends on a module. */
public class SharedViolator {

  Violator violator = new Violator();
}
