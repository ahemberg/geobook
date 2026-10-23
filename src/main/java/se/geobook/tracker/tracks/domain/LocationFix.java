package se.geobook.tracker.tracks.domain;

/** A raw background position, including the metadata needed for history cleaning. */
public record LocationFix(Recording.Point point, Integer accuracy, String device) {}
