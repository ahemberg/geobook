package se.geobook.tracker.heatmap.domain;

import java.time.Instant;
import java.time.LocalDate;

/** One stored position of a visible track or history day, with its local date if known. */
public record HeatPosition(
    long trackId, int segment, double latitude, double longitude, Instant time, LocalDate date) {}
