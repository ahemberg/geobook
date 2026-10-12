package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

// IDs use the same name throughout storage and the public track representation.
@SuppressWarnings("PMD.ShortVariable")
public record Track(long id, String name, String colour, boolean visible, String identity) {
  public String revision() {
    String state =
        id
            + ":"
            + identity
            + ":"
            + ContentIdentity.original(name.getBytes(StandardCharsets.UTF_8))
            + ":"
            + colour
            + ":"
            + visible;
    return "\"" + ContentIdentity.original(state.getBytes(StandardCharsets.UTF_8)) + "\"";
  }

  public record Imported(long trackId, long originalId, boolean duplicate) {}

  public record Overview(
      Track track,
      Instant startTime,
      int pointCount,
      int unknownDateCount,
      List<List<List<BigDecimal>>> geometry) {
    public Overview {
      geometry = List.copyOf(TrackGeometry.copy(geometry));
    }
  }

  public record Point(
      int ordinal, Recording.Point source, PointLocalTime.Attribution attribution) {}

  public record Detail(
      Track track,
      int segmentCount,
      List<Point> points,
      List<List<List<BigDecimal>>> geometry,
      List<Long> originalIds) {
    public Detail {
      points = List.copyOf(points);
      geometry = List.copyOf(TrackGeometry.copy(geometry));
      originalIds = List.copyOf(originalIds);
    }
  }

  public record Original(String filename, byte[] bytes) {
    public Original {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public record Neighbour(String colour, TrackColours.Bounds bounds) {}

  public record Prepared(
      Recording recording,
      String identity,
      TrackColours.Bounds bounds,
      List<PointLocalTime.Attribution> local) {
    public Prepared {
      local = List.copyOf(local);
    }
  }
}
