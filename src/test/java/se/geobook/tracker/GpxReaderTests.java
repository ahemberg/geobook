package se.geobook.tracker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpxReaderTests {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<trkpt lat='91' lon='0'/>",
        "<trkpt lat='0' lon='181'/>",
        "<trkpt lat='NaN' lon='0'/>",
        "<trkpt lon='0'/>",
        "<trkpt lat='0' lon='0'><ele>NaN</ele></trkpt>",
        "<trkpt lat='0' lon='0'><ele>1<extra>2</extra></ele></trkpt>",
        "<trkpt lat='0' lon='0'><time>2025-01-01T12:<extra>30</extra>:00Z</time></trkpt>",
        "<trkpt lat='0' lon='0'><time>yesterday</time></trkpt>",
        "<trkpt lat='0' lon='0'><time>2025-01-01T12:00:00</time></trkpt>",
        "<trkpt lat='0' lon='0'><ele>1</ele><ele>2</ele></trkpt>"
      })
  void invalidMeasurementsAreRejected(String point) {
    String xml =
        "<gpx xmlns='http://www.topografix.com/GPX/1/1'><trk><trkseg>"
            + point
            + "</trkseg></trk></gpx>";
    assertThatThrownBy(() -> GpxReader.read(xml.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<!DOCTYPE gpx [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><gpx>&xxe;</gpx>",
        "<gpx>",
        "<gpx/>",
        "<gpx xmlns='https://unrelated.example'/>",
        "<gpx xmlns='http://www.topografix.com/GPX/1/1'><trk/><trk/></gpx>",
        "<gpx xmlns='http://www.topografix.com/GPX/1/1'><trk><trkseg/></trk></gpx>"
      })
  void unsafeOrUnsupportedDocumentsAreRejected(String xml) {
    assertThatThrownBy(() -> GpxReader.read(xml.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
