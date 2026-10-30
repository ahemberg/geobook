package se.geobook.tracker.access.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.service.SessionService;

@Controller
final class LoginPageController {
  private final SessionService sessions;

  LoginPageController(SessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/login")
  String login() {
    return sessions.mode() == AuthMode.PUBLIC ? "redirect:/map" : "forward:/login.html";
  }
}
