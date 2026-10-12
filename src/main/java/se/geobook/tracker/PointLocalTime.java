package se.geobook.tracker;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneRulesProvider;
import java.util.List;
import net.iakovlev.timeshape.TimeZoneEngine;

public final class PointLocalTime {
  private static final String BOUNDARY_VERSION = "2026b";

  private PointLocalTime() {}

  public record Attribution(
      String timezone,
      String localDate,
      String localTime,
      String timeStatus,
      String boundaryVersion,
      String tzdbVersion) {}

  // Load once, including when a migration attributes existing recordings.
  private static final class Boundaries {
    private static final TimeZoneEngine ENGINE = TimeZoneEngine.initialize();
  }

  public static Attribution resolve(double latitude, double longitude, Instant time) {
    if (time == null) {
      return new Attribution(null, null, null, "untimed", BOUNDARY_VERSION, null);
    }
    List<ZoneId> zones = Boundaries.ENGINE.queryAll(latitude, longitude);
    if (zones.size() != 1 || zones.getFirst().getId().startsWith("Etc/")) {
      return new Attribution(null, null, null, "unresolved", BOUNDARY_VERSION, null);
    }
    ZoneId zone = zones.getFirst();
    try {
      ZonedDateTime local = time.atZone(zone);
      if (local.getYear() < 1 || local.getYear() > 9999) {
        return new Attribution(null, null, null, "unresolved", BOUNDARY_VERSION, null);
      }
      return new Attribution(
          zone.getId(),
          local.toLocalDate().toString(),
          local.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
          "resolved",
          BOUNDARY_VERSION,
          ZoneRulesProvider.getVersions(zone.getId()).lastKey());
    } catch (DateTimeException exception) {
      return new Attribution(null, null, null, "unresolved", BOUNDARY_VERSION, null);
    }
  }
}
