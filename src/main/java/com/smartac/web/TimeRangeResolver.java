package com.smartac.web;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class TimeRangeResolver {

  private TimeRangeResolver() {}

  public record Range(Instant from, Instant to) {}

  public static Range resolve(TimeRange range) {
    ZoneId z = ZoneId.systemDefault();
    ZonedDateTime nowZ = ZonedDateTime.now(z);
    Instant to = nowZ.toInstant();
    if (range == TimeRange.all) {
      return new Range(Instant.EPOCH, to);
    }
    LocalDate today = nowZ.toLocalDate();
    LocalDate startDate =
        switch (range) {
          case today -> today;
          case week -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          case month -> today.withDayOfMonth(1);
          case year -> today.withDayOfYear(1);
          case all -> today; // handled above
        };
    Instant from = startDate.atStartOfDay(z).toInstant();
    return new Range(from, to);
  }
}
