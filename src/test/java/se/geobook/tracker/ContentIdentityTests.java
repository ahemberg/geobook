package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentIdentityTests {
  @Test
  void equivalentNumbersAndInstantsIgnoreNamesAndSourceColours() {
    assertThat(
            identity(
                "<trk><name>One</name><trkseg><trkpt lat='1.2300'"
                    + " lon='-0.0'><ele>1e2</ele><time>2025-01-01T01:00:00+01:00</time></trkpt></trkseg></trk>"))
        .isEqualTo(
            identity(
                "<trk><name>Two</name><extensions><colour>red</colour></extensions><trkseg><trkpt"
                    + " lat='1.23'"
                    + " lon='0'><ele>100.00</ele><time>2025-01-01T00:00:00Z</time></trkpt></trkseg></trk>"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<trkseg><trkpt lat='1.000000000000000002' lon='2'/></trkseg>",
        "<trkseg><trkpt lat='1.000000000000000001' lon='2'><ele>0</ele></trkpt></trkseg>",
        "<trkseg/><trkseg><trkpt lat='1.000000000000000001' lon='2'/></trkseg>",
        "<trkseg><trkpt lat='1.000000000000000001' lon='2'/></trkseg><trkseg/>",
        "<trkseg><trkpt lat='1.000000000000000001' lon='2'/><trkpt lat='1.000000000000000001'"
            + " lon='2'/></trkseg>"
      })
  void precisionMissingValuesAndSegmentStructureRemainDistinct(String segments) {
    assertThat(identity("<trk>" + segments + "</trk>"))
        .isNotEqualTo(
            identity("<trk><trkseg><trkpt lat='1.000000000000000001' lon='2'/></trkseg></trk>"));
  }

  private static String identity(String track) {
    return ContentIdentity.recording(
        GpxReader.read(
            ("<gpx xmlns='http://www.topografix.com/GPX/1/1'>" + track + "</gpx>")
                .getBytes(StandardCharsets.UTF_8)));
  }
}
