package se.geobook.tracker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

final class GpxReader {
  private static final int SINGLE_TRACK = 1;
  private static final int MAX_POINTS = 250_000;

  private GpxReader() {}

  static Recording read(byte[] bytes) {
    List<Element> tracks = tracks(bytes);
    if (tracks.size() != SINGLE_TRACK) {
      throw new IllegalArgumentException("Choose an export containing exactly one track.");
    }
    return recording(tracks.getFirst());
  }

  static List<Element> tracks(byte[] bytes) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new DefaultHandler() {
            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
              throw exception;
            }
          });
      Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
      if (!"gpx".equals(root.getLocalName())
          || root.getNamespaceURI() == null
          || !List.of("http://www.topografix.com/GPX/1/1", "http://www.topografix.com/GPX/1/0")
              .contains(root.getNamespaceURI())) {
        throw new IllegalArgumentException("Choose a GPX 1.0 or 1.1 export.");
      }
      List<Element> tracks = children(root, "trk");
      if (tracks.isEmpty()) {
        throw new IllegalArgumentException("The export contains no tracks.");
      }
      return tracks;

    } catch (ParserConfigurationException | SAXException | IOException exception) {
      throw new IllegalArgumentException(
          "The GPX could not be read. Check the file and try again.", exception);
    }
  }

  static Recording recording(Element track) {
    List<Element> segments = children(track, "trkseg");
    List<Recording.Point> points = readPoints(segments);
    String name = java.util.Objects.requireNonNullElse(text(track, "name"), "Unnamed track");
    return new Recording(
        name.isBlank() ? "Unnamed track" : name, segments.size(), List.copyOf(points));
  }

  private static List<Recording.Point> readPoints(List<Element> segments) {
    List<Recording.Point> points = new ArrayList<>();
    for (int segment = 0; segment < segments.size(); segment++) {
      for (Element point : children(segments.get(segment), "trkpt")) {
        points.add(point(point, segment));
        if (points.size() > MAX_POINTS) {
          throw new IllegalArgumentException("A track can contain at most 250,000 points.");
        }
      }
    }
    if (points.isEmpty()) {
      throw new IllegalArgumentException("The track has no recorded positions.");
    }
    return points;
  }

  private static Recording.Point point(Element element, int segment) {
    BigDecimal latitude = coordinate(element.getAttribute("lat"), 90);
    BigDecimal longitude = coordinate(element.getAttribute("lon"), 180);
    String elevation = text(element, "ele");
    String time = text(element, "time");
    try {
      BigDecimal recordedElevation = elevation == null ? null : new BigDecimal(elevation);
      if (recordedElevation != null && !Double.isFinite(recordedElevation.doubleValue())) {
        throw new IllegalArgumentException("A recorded elevation is out of range.");
      }
      return new Recording.Point(
          segment,
          latitude,
          longitude,
          recordedElevation,
          time == null ? null : Instant.parse(time));
    } catch (NumberFormatException | DateTimeParseException exception) {
      throw new IllegalArgumentException(
          "A recorded elevation or timestamp is invalid.", exception);
    }
  }

  private static BigDecimal coordinate(String value, int limit) {
    BigDecimal number;
    try {
      number = new BigDecimal(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("A recorded latitude or longitude is invalid.", exception);
    }
    if (number.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
      throw new IllegalArgumentException("A recorded latitude or longitude is out of range.");
    }
    return number;
  }

  private static String text(Element element, String name) {
    List<Element> matches = children(element, name);
    if (matches.size() > SINGLE_TRACK) {
      throw new IllegalArgumentException("A GPX element contains repeated " + name + " values.");
    }
    if (matches.isEmpty()) {
      return null;
    }
    Element scalar = matches.getFirst();
    if (scalar.getElementsByTagName("*").getLength() > 0) {
      throw new IllegalArgumentException("A GPX " + name + " value contains unexpected markup.");
    }
    return scalar.getTextContent().strip();
  }

  private static List<Element> children(Element element, String name) {
    List<Element> matches = new ArrayList<>();
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element child
          && name.equals(child.getLocalName())
          && element.getNamespaceURI().equals(child.getNamespaceURI())) {
        matches.add(child);
      }
    }
    return matches;
  }
}
