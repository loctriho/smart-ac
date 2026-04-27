package com.smartac.web.admin;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Forwards {@code GET /admin/} to {@code index.html} (static admin SPA under {@code classpath:/static/admin/}). */
@Controller
public class AdminSpaIndexController {

  @GetMapping(value = "/admin/", produces = MediaType.TEXT_HTML_VALUE)
  public String adminRoot() {
    return "forward:/admin/index.html";
  }
}
