package se.geobook.tracker.access.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.geobook.tracker.access.domain.NextPath;
import se.geobook.tracker.access.domain.SignInResult;
import se.geobook.tracker.access.service.SessionService;

@RestController
final class SessionController {
  private final SessionService sessions;
  private final ClientConnection connection;

  SessionController(SessionService sessions, ClientConnection connection) {
    this.sessions = sessions;
    this.connection = connection;
  }

  record SignIn(String username, String password, String code, String next) {
    @Override
    public String toString() {
      return "SignIn[username=" + username + "]";
    }
  }

  record SignedIn(String username, String next) {}

  record Session(String username, boolean twoFactor) {}

  record Failure(String message) {}

  /** A 401 telling the login page to ask for the 2FA code. */
  record CodeNeeded(String message, boolean codeRequired) {}

  @PostMapping("/api/session")
  ResponseEntity<?> signIn(
      @RequestBody SignIn input, HttpServletRequest request, HttpServletResponse response) {
    final SignInResult result =
        sessions.signIn(
            Objects.requireNonNullElse(input.username(), ""),
            Objects.requireNonNullElse(input.password(), ""),
            input.code(),
            connection.client(request));
    return switch (result) {
      case SignInResult.Limited _ ->
          ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
              .body(new Failure(TooManyAttempts.MESSAGE));
      case SignInResult.Rejected _ ->
          ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(new Failure("Wrong username or password."));
      case SignInResult.CodeRequired _ ->
          ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(new CodeNeeded("Enter the code from your authenticator app.", true));
      case SignInResult.WrongCode _ ->
          ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new CodeNeeded("Wrong code.", true));
      case SignInResult.SignedIn signedIn -> {
        SessionCookies.read(request).ifPresent(sessions::signOut);
        SessionCookies.issue(
            response, signedIn.token(), sessions.maxAge(), connection.secure(request));
        yield ResponseEntity.ok(new SignedIn(input.username(), NextPath.resolve(input.next())));
      }
    };
  }

  @GetMapping("/api/session")
  Session session() {
    return new Session(sessions.ownerUsername(), sessions.twoFactorActive());
  }

  @DeleteMapping("/api/session")
  ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
    SessionCookies.read(request).ifPresent(sessions::signOut);
    SessionCookies.clear(response, connection.secure(request));
    return ResponseEntity.noContent().build();
  }
}
