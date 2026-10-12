package se.geobook.tracker.tracks.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;

// IDs match the public archive and track contracts.
@SuppressWarnings("PMD.ShortVariable")
public record OriginalExport(
    long id, String filename, long byteCount, String identity, List<SourceTrack> tracks) {
  public OriginalExport {
    tracks = List.copyOf(tracks);
  }

  public record Source(long id, String identity) {}

  public record SourceTrack(Track track, List<Long> originalIds) {
    public SourceTrack {
      originalIds = List.copyOf(originalIds);
    }

    public boolean retained() {
      return originalIds.size() > 1;
    }

    private String revision() {
      return track.revision() + ":" + originalIds;
    }
  }

  public String revision() {
    // Include content identity because SQLite can reuse IDs after deletion.
    String state = id + ":" + identity + ":" + tracks.stream().map(SourceTrack::revision).toList();
    return "\"" + ContentIdentity.original(state.getBytes(StandardCharsets.UTF_8)) + "\"";
  }
}
