package se.geobook.tracker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record Recording(String name, int segmentCount, List<Point> points) {
  record Point(
      int segment, BigDecimal latitude, BigDecimal longitude, BigDecimal elevation, Instant time) {}
}
