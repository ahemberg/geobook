package se.geobook.tracker.access.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The account page. In public mode {@link SecurityConfiguration} answers 404 instead. */
@Controller
final class AccountPageController {
  @GetMapping("/account")
  String account() {
    return "forward:/account.html";
  }
}
