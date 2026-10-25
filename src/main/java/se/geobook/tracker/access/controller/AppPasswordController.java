package se.geobook.tracker.access.controller;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.access.service.AppPasswordService;
import se.geobook.tracker.access.service.AppPasswordService.Summary;

@RestController
@SuppressWarnings("PMD.ShortVariable")
final class AppPasswordController {
  private final AppPasswordService appPasswords;

  AppPasswordController(AppPasswordService appPasswords) {
    this.appPasswords = appPasswords;
  }

  record Creation(String name, String scope, Instant expiresAt) {}

  record Listed(
      String id,
      String name,
      String scope,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt) {
    static Listed from(Summary summary) {
      return new Listed(
          summary.id(),
          summary.name(),
          summary.scope(),
          summary.createdAt(),
          summary.expiresAt().orElse(null),
          summary.lastUsedAt().orElse(null));
    }
  }

  /** The listed fields plus the secret, shown once. */
  record Created(@JsonUnwrapped Listed appPassword, String password) {
    static Created from(AppPasswordService.Created created) {
      return new Created(Listed.from(created.summary()), created.secret());
    }

    @Override
    public String toString() {
      return "Created[id=" + appPassword.id() + ", password=<redacted>]";
    }
  }

  @GetMapping("/api/app-passwords")
  List<Listed> list() {
    return appPasswords.summaries().stream().map(Listed::from).toList();
  }

  @PostMapping("/api/app-passwords")
  ResponseEntity<Created> create(@RequestBody Creation input) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Created.from(
                appPasswords.create(
                    input.name(), input.scope(), Optional.ofNullable(input.expiresAt()))));
  }

  @DeleteMapping("/api/app-passwords/{id}")
  ResponseEntity<Void> revoke(@PathVariable String id) {
    return appPasswords.revoke(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
