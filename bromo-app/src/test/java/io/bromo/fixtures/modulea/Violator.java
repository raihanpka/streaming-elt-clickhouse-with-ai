package io.bromo.fixtures.modulea;

import io.bromo.fixtures.moduleb.Helper;

/** Fixture class that deliberately breaks the module rule. */
public class Violator {

  Helper helper = new Helper();
}
