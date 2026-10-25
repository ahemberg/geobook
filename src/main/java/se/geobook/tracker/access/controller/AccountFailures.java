package se.geobook.tracker.access.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import se.geobook.tracker.access.service.TooManyAttemptsException;
import se.geobook.tracker.access.service.WrongPasswordException;

/**
 * Turns the account services' exceptions into 400 with their message, and limited clients into 429.
 * A wrong password is 400, not 401, because the frontend sends every 401 to the login page.
 */
@RestControllerAdvice(
    assignableTypes = {
      AccountController.class,
      AppPasswordController.class,
      TwoFactorController.class
    })
final class AccountFailures {
  @ExceptionHandler({
    WrongPasswordException.class,
    IllegalArgumentException.class,
    IllegalStateException.class
  })
  ResponseEntity<Failure> invalid(RuntimeException exception) {
    return ResponseEntity.badRequest().body(new Failure(exception.getMessage()));
  }

  @ExceptionHandler(TooManyAttemptsException.class)
  ResponseEntity<Failure> limited() {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(new Failure(TooManyAttempts.MESSAGE));
  }
}
