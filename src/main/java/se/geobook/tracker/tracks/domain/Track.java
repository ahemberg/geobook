package se.geobook.tracker.tracks.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// IDs use the same name throughout storage and the public track representation.
@SuppressWarnings("PMD.ShortVariable")
public record Track(
    long id,
    String name,
    String colour,
    boolean visible,
    String identity,
    String kind,
    String historyDate) {
  public boolean isHistory() {
    return "history".equals(kind);
  }

  public PlaybackSettings geometrySettings(PlaybackSettings settings) {
    return isHistory() ? HistoryDay.GEOMETRY : settings;
  }

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
      List<List<List<BigDecimal>>> geometry,
      List<List<List<BigDecimal>>> gaps) {
    public Overview {
      geometry = List.copyOf(TrackGeometry.copy(geometry));
      gaps = List.copyOf(TrackGeometry.copy(gaps));
    }
  }

  public static final class OverviewBuilder {
    private final Track track;
    private final Instant start;
    private final PlaybackSettings settings;
    private final List<List<List<BigDecimal>>> gaps = new ArrayList<>();
    private final List<List<List<BigDecimal>>> geometry = new ArrayList<>();
    private int first = -1;
    private int last = -1;
    private int count;
    private int unknown;

    public OverviewBuilder(Track track, Instant start, PlaybackSettings settings) {
      this.track = track;
      this.start = start;
      this.settings = settings;
    }

    public void add(
        int next,
        int runLast,
        String localDate,
        boolean breakBefore,
        long secondsBefore,
        List<BigDecimal> position) {
      if (next != first) {
        boolean adjacent = first >= 0 && last + 1 == next;
        boolean broken = breakBefore || secondsBefore > settings.gapSeconds();
        if (adjacent && broken && settings.showGapConnections()) {
          gaps.add(List.of(geometry.getLast().getLast(), List.copyOf(position)));
        }
        if (!adjacent || broken) {
          geometry.add(new ArrayList<>());
        }
        first = next;
        last = runLast;
        count += last - first + 1;
        if (localDate == null) {
          unknown += last - first + 1;
        }
      }
      geometry.getLast().add(List.copyOf(position));
    }

    public Overview build() {
      return new Overview(track, start, count, unknown, geometry, gaps);
    }
  }

  public record Point(
      int ordinal, Recording.Point source, PointLocalTime.Attribution attribution) {}

  public record Detail(
      Track track,
      int segmentCount,
      List<Point> points,
      List<List<List<BigDecimal>>> geometry,
      List<List<List<BigDecimal>>> gaps,
      List<Long> originalIds,
      HistoryDay.FixCounts fixCounts) {
    public Detail {
      points = List.copyOf(points);
      geometry = List.copyOf(TrackGeometry.copy(geometry));
      gaps = List.copyOf(TrackGeometry.copy(gaps));
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
      List<PointLocalTime.Attribution> local,
      List<String> countries,
      List<OverviewRun> overview) {
    public Prepared {
      local = List.copyOf(local);
      overview = List.copyOf(overview);
      countries = java.util.Collections.unmodifiableList(new ArrayList<>(countries));
    }
  }
}
