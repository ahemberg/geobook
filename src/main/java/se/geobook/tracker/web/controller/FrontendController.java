package se.geobook.tracker.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class FrontendController {
  @GetMapping({"/map", "/map/"})
  String map() {
    return "forward:/index.html";
  }
}
