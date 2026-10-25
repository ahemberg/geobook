package se.geobook.tracker.access.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.access.domain.AppPassword;
import se.geobook.tracker.access.service.AppPasswordService;

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
    static Listed from(AppPassword password) {
      return new Listed(
          password.id(),
          password.name(),
          password.scope().stored(),
          password.createdAt(),
          password.expiresAt().orElse(null),
          password.lastUsedAt().orElse(null));
    }
  }

  record Created(
      String id,
      String name,
      String scope,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      String password) {
    @Override
    public String toString() {
      return "Created[id=" + id + ", password=<redacted>]";
    }
  }

  record Failure(String message) {}

  @GetMapping("/api/app-passwords")
  List<Listed> list() {
    return appPasswords.list().stream().map(Listed::from).toList();
  }

  @PostMapping("/api/app-passwords")
  ResponseEntity<Created> create(@RequestBody Creation input) {
    final AppPasswordService.Created created =
        appPasswords.create(input.name(), input.scope(), Optional.ofNullable(input.expiresAt()));
    final Listed listed = Listed.from(created.password());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new Created(
                listed.id(),
                listed.name(),
                listed.scope(),
                listed.createdAt(),
                listed.expiresAt(),
                listed.lastUsedAt(),
                created.token().value()));
  }

  @DeleteMapping("/api/app-passwords/{id}")
  ResponseEntity<Void> revoke(@PathVariable String id) {
    return appPasswords.revoke(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Failure> invalid(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(new Failure(exception.getMessage()));
  }
}
