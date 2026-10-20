package io.bromo.fixtures.shared;

import io.bromo.fixtures.slicea.Violator;

/** Fixture class in shared that deliberately depends on a slice. */
public class SharedViolator {

  Violator violator = new Violator();
}
