package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentIdentity {
  private ContentIdentity() {}

  public static String original(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  public static String recording(Recording recording) {
    MessageDigest digest = digest();
    update(digest, Integer.toString(recording.segmentCount()));
    for (Recording.Point point : recording.points()) {
      update(digest, Integer.toString(point.segment()));
      update(digest, decimal(point.latitude()));
      update(digest, decimal(point.longitude()));
      update(digest, decimal(point.elevation()));
      update(digest, point.time() == null ? "missing" : point.time().toString());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String history(HistoryDay day) {
    MessageDigest digest = digest();
    update(digest, recording(day.recording()));
    update(digest, Integer.toString(day.keptFixCount()));
    update(digest, Integer.toString(day.droppedFixCount()));
    for (PlaceVisit visit : day.placeVisits()) {
      update(digest, visit.name());
      update(digest, decimal(visit.latitude()));
      update(digest, decimal(visit.longitude()));
      update(digest, visit.startTime().toString());
      update(digest, visit.endTime().toString());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String decimal(BigDecimal value) {
    return value == null ? "missing" : value.stripTrailingZeros().toString();
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) '\n');
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("The Java runtime must support SHA-256.", exception);
    }
  }
}
