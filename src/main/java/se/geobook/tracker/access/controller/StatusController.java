package se.geobook.tracker.access.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.access.service.SessionService;

/** Open without a session, so it must reveal nothing about the owner or the collection. */
@RestController
final class StatusController {
  private final SessionService sessions;

  StatusController(SessionService sessions) {
    this.sessions = sessions;
  }

  record Status(String authMode) {}

  @GetMapping("/api/status")
  Status status() {
    return new Status(sessions.mode().value());
  }
}
