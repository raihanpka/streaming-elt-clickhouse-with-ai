package io.bromo.shared.contract;

import java.util.Locale;
import java.util.Optional;

/** Column types a contract field can use. */
public enum FieldType {
  STRING("String", false),
  LOW_CARDINALITY_STRING("LowCardinality(String)", false),
  FLOAT64("Float64", true),
  INT64("Int64", true),
  BOOL("Bool", false);

  private final String clickhouseType;
  private final boolean numeric;

  FieldType(String clickhouseType, boolean numeric) {
    this.clickhouseType = clickhouseType;
    this.numeric = numeric;
  }

  /** The ClickHouse type name for a non nullable column. */
  public String clickhouseType() {
    return clickhouseType;
  }

  /** True for types that mart measures can aggregate. */
  public boolean numeric() {
    return numeric;
  }

  /** True for types that can be a mart dimension. */
  public boolean textual() {
    return this == STRING || this == LOW_CARDINALITY_STRING;
  }

  /** Parses the lowercase name used in the contract file. */
  public static Optional<FieldType> parse(String name) {
    try {
      return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
