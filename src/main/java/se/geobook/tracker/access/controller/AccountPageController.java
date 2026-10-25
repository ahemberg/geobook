package se.geobook.tracker.access.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.service.SessionService;

@Controller
final class AccountPageController {
  private final SessionService sessions;

  AccountPageController(SessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/account")
  String account() {
    return sessions.mode() == AuthMode.PUBLIC ? "redirect:/map" : "forward:/account.html";
  }
}
