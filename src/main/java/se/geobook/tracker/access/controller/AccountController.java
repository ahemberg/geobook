package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.access.service.AccountService;

@RestController
final class AccountController {
  private final AccountService account;
  private final ClientConnection connection;

  AccountController(AccountService account, ClientConnection connection) {
    this.account = account;
    this.connection = connection;
  }

  record PasswordChange(String currentPassword, String newPassword) {
    @Override
    public String toString() {
      return "PasswordChange[<redacted>]";
    }
  }

  @PutMapping("/api/account/password")
  ResponseEntity<Void> changePassword(
      @RequestBody PasswordChange input, HttpServletRequest request) {
    account.changePassword(
        Objects.requireNonNullElse(input.currentPassword(), ""),
        Objects.requireNonNullElse(input.newPassword(), ""),
        SessionCookies.read(request).orElse(null));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/account/sessions")
  ResponseEntity<Void> signOutEverywhere(HttpServletRequest request, HttpServletResponse response) {
    account.signOutEverywhere();
    SessionCookies.clear(response, connection.secure(request));
    return ResponseEntity.noContent().build();
  }
}
