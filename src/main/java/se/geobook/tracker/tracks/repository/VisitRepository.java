package se.geobook.tracker.tracks.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import se.geobook.tracker.tracks.domain.DateRange;
import se.geobook.tracker.tracks.domain.Visit;

@Repository
public class VisitRepository {
  private final JdbcTemplate jdbc;

  VisitRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Visit.Candidate> candidates(DateRange range) {
    Map<Visit.Key, List<Visit.Evidence>> evidence = new LinkedHashMap<>();
    Map<Visit.Key, String> states = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT DISTINCT p.country_code, p.local_date, t.id, t.name, COALESCE(r.state, 'pending') AS state
        FROM track_point p JOIN track t ON t.id = p.track_id
        LEFT JOIN visit_review r ON r.country_code = p.country_code AND r.local_date = p.local_date
        WHERE p.country_code IS NOT NULL AND p.local_date IS NOT NULL
          AND (? IS NULL OR p.local_date BETWEEN ? AND ?)
        ORDER BY p.local_date DESC, p.country_code, t.id
        """,
        row -> {
          Visit.Key key = new Visit.Key(row.getString("country_code"), row.getString("local_date"));
          evidence
              .computeIfAbsent(key, ignored -> new ArrayList<>())
              .add(new Visit.Evidence(row.getLong("id"), row.getString("name")));
          states.put(key, row.getString("state"));
        },
        range.from(),
        range.from(),
        range.through());
    return evidence.entrySet().stream()
        .map(
            entry ->
                new Visit.Candidate(entry.getKey(), states.get(entry.getKey()), entry.getValue()))
        .toList();
  }

  public int unresolvedPoints(DateRange range) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            """
            SELECT count(*) FROM track_point p JOIN track t ON t.id = p.track_id
            WHERE (p.country_code IS NULL OR p.local_date IS NULL)
              AND (? IS NULL OR p.local_date BETWEEN ? AND ?)
            """,
            Integer.class,
            range.from(),
            range.from(),
            range.through()));
  }

  public List<Visit.Summary> summaries(DateRange range) {
    return jdbc.query(
        """
        SELECT p.country_code, min(p.local_date) AS first_visit, max(p.local_date) AS latest_visit,
          count(DISTINCT p.local_date) AS recorded_days
        FROM track_point p JOIN track t ON t.id = p.track_id
        JOIN visit_review r ON r.country_code = p.country_code AND r.local_date = p.local_date
        WHERE r.state = 'confirmed' AND (? IS NULL OR p.local_date BETWEEN ? AND ?)
        GROUP BY p.country_code ORDER BY p.country_code
        """,
        (row, index) ->
            new Visit.Summary(
                row.getString("country_code"),
                row.getString("first_visit"),
                row.getString("latest_visit"),
                row.getInt("recorded_days")),
        range.from(),
        range.from(),
        range.through());
  }

  public void review(Visit.Review review) {
    for (Visit.Key key : review.visits()) {
      Integer count =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM track_point p JOIN track t ON t.id = p.track_id
              WHERE p.country_code = ? AND p.local_date = ?
              """,
              Integer.class,
              key.country(),
              key.date());
      if (count == null || count == 0) {
        throw new IllegalArgumentException(
            "A selected country day no longer has recorded evidence. Reload visits and retry.");
      }
      jdbc.update(
          """
          INSERT INTO visit_review(country_code, local_date, state) VALUES (?, ?, ?)
          ON CONFLICT(country_code, local_date) DO UPDATE SET state = excluded.state
          """,
          key.country(),
          key.date(),
          review.state());
    }
  }
}
