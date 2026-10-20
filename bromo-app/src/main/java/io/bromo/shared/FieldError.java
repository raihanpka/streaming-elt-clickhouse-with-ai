package io.bromo.shared;

/** One invalid field in a request: a JSON Pointer into the body and a reason. */
public record FieldError(String pointer, String detail) {}
