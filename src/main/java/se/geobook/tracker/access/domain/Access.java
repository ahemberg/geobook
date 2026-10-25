package se.geobook.tracker.access.domain;

/** The authenticated caller of one request: the owner, through a credential with a scope. */
public record Access(String username, Scope scope) {}
