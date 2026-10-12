package se.geobook.tracker.tracks.controller;

import java.util.List;
import se.geobook.tracker.tracks.domain.PointCountry;
import se.geobook.tracker.tracks.domain.Visit;
import tools.jackson.databind.JsonNode;

final class VisitResponses {
  private VisitResponses() {}

  record Evidence(long trackId, String name) {}

  record Candidate(String country, String name, String date, String state, List<Evidence> tracks) {}

  record Reviewable(List<Candidate> candidates, int unresolvedPoints) {}

  record Summary(
      String country,
      String name,
      String firstVisit,
      String latestVisit,
      int recordedDays,
      List<List<Double>> bounds) {}

  static Candidate from(Visit.Candidate candidate) {
    return new Candidate(
        candidate.key().country(),
        PointCountry.country(candidate.key().country()).name(),
        candidate.key().date(),
        candidate.state(),
        candidate.tracks().stream()
            .map(track -> new Evidence(track.trackId(), track.name()))
            .toList());
  }

  static Summary from(Visit.Summary summary) {
    PointCountry.Country country = PointCountry.country(summary.country());
    return new Summary(
        country.code(),
        country.name(),
        summary.firstVisit(),
        summary.latestVisit(),
        summary.recordedDays(),
        List.of(
            List.of(country.south(), country.west()), List.of(country.north(), country.east())));
  }

  static Visit.Review read(JsonNode input) {
    if (!input.isObject()
        || input.size() != 2
        || !input.path("state").isTextual()
        || !input.path("visits").isArray()) {
      throw new IllegalArgumentException("Supply a review state and a list of country days.");
    }
    List<Visit.Key> keys = new java.util.ArrayList<>();
    for (JsonNode key : input.get("visits")) {
      keys.add(readKey(key));
    }
    return new Visit.Review(keys, input.get("state").asText());
  }

  private static Visit.Key readKey(JsonNode key) {
    if (!key.isObject()
        || key.size() != 2
        || !key.path("country").isTextual()
        || !key.path("date").isTextual()) {
      throw new IllegalArgumentException("Each visit needs a country and recorded day.");
    }
    return new Visit.Key(key.get("country").asText(), key.get("date").asText());
  }
}
