package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.access.domain.RecoveryCode;
import se.geobook.tracker.access.domain.TwoFactorSetup;
import se.geobook.tracker.access.service.TwoFactorService;

/** 2FA management. Lives under {@code /api/account}, which only browser sessions may use. */
@RestController
final class TwoFactorController {
  private final TwoFactorService twoFactor;
  private final ClientConnection connection;

  TwoFactorController(TwoFactorService twoFactor, ClientConnection connection) {
    this.twoFactor = twoFactor;
    this.connection = connection;
  }

  record Start(String password) {
    @Override
    public String toString() {
      return "Start[<redacted>]";
    }
  }

  record Confirm(String code) {
    @Override
    public String toString() {
      return "Confirm[<redacted>]";
    }
  }

  record Confirmed(String recoveryCode) {
    @Override
    public String toString() {
      return "Confirmed[<redacted>]";
    }
  }

  record TurnOff(String password, String code) {
    @Override
    public String toString() {
      return "TurnOff[<redacted>]";
    }
  }

  @PostMapping("/api/account/2fa/setup")
  TwoFactorSetup start(@RequestBody Start input, HttpServletRequest request) {
    return twoFactor.start(
        Objects.requireNonNullElse(input.password(), ""), connection.client(request));
  }

  @PostMapping("/api/account/2fa/confirm")
  Confirmed confirm(@RequestBody Confirm input, HttpServletRequest request) {
    final RecoveryCode recovery =
        twoFactor.confirm(
            Objects.requireNonNullElse(input.code(), "").strip(),
            SessionCookies.token(request),
            connection.client(request));
    return new Confirmed(recovery.value());
  }

  @PostMapping("/api/account/2fa/off")
  ResponseEntity<Void> turnOff(@RequestBody TurnOff input, HttpServletRequest request) {
    twoFactor.turnOff(
        Objects.requireNonNullElse(input.password(), ""),
        Objects.requireNonNullElse(input.code(), "").strip(),
        connection.client(request));
    return ResponseEntity.noContent().build();
  }
}
