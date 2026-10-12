package se.geobook.tracker.tracks.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;

public final class PointCountry {
  public static final String VERSION = "natural-earth-5.1.2-admin0-10m";
  private static final double UNCERTAINTY = 0.01;
  private static final GeometryFactory FACTORY = new GeometryFactory();

  private PointCountry() {}

  public record Country(
      String code, String name, double south, double north, double west, double east) {}

  private record Area(Country country, PreparedGeometry polygon, IndexedFacetDistance boundary) {}

  private static final class Boundaries {
    private static final Map<String, Country> COUNTRIES = new HashMap<>();
    private static final STRtree INDEX = load();

    // Each resource polygon needs its own spatial index entry.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static STRtree load() {
      STRtree index = new STRtree();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(
                  new GZIPInputStream(
                      Objects.requireNonNull(
                          PointCountry.class.getResourceAsStream("/geography/countries.tsv.gz"))),
                  StandardCharsets.UTF_8))) {
        WKTReader parser = new WKTReader();
        String line = reader.readLine();
        while (line != null) {
          String[] fields = line.split("\t", 3);
          Geometry geometry = parser.read(fields[2]);
          Envelope bounds = geometry.getEnvelopeInternal();
          Country country =
              new Country(
                  fields[0],
                  fields[1],
                  bounds.getMinY(),
                  bounds.getMaxY(),
                  bounds.getMinX(),
                  bounds.getMaxX());
          COUNTRIES.put(country.code(), country);
          line = reader.readLine();
          for (int part = 0; part < geometry.getNumGeometries(); part++) {
            Geometry polygon = geometry.getGeometryN(part);
            index.insert(
                polygon.getEnvelopeInternal(),
                new Area(
                    country,
                    PreparedGeometryFactory.prepare(polygon),
                    new IndexedFacetDistance(polygon.getBoundary())));
          }
        }
        index.build();
        return index;
      } catch (IOException | ParseException exception) {
        throw new IllegalStateException(
            "Bundled country boundaries could not be loaded.", exception);
      }
    }
  }

  public static Country country(String code) {
    Country country = Boundaries.COUNTRIES.get(code);
    if (country == null) {
      throw new IllegalArgumentException("Unknown country.");
    }
    return country;
  }

  // Invalid coordinates, boundary proximity and overlapping countries are separate rejection cases.
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static String resolve(double latitude, double longitude) {
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || Math.abs(latitude) > 90
        || Math.abs(longitude) > 180) {
      throw new IllegalArgumentException("Invalid country lookup coordinates.");
    }
    Geometry point = FACTORY.createPoint(new Coordinate(longitude, latitude));
    Envelope search = new Envelope(point.getEnvelopeInternal());
    search.expandBy(UNCERTAINTY);
    String found = null;
    for (Object item : Boundaries.INDEX.query(search)) {
      Area area = (Area) item;
      if (area.boundary().isWithinDistance(point, UNCERTAINTY)) {
        return null;
      }
      if (area.polygon().contains(point)) {
        if (found != null && !found.equals(area.country().code())) {
          return null;
        }
        found = area.country().code();
      }
    }
    return found;
  }
}
