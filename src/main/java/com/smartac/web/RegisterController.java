package com.smartac.web;

import com.smartac.admin.service.AdminRegistrationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RegisterController {

  private final AdminRegistrationService adminRegistrationService;

  public RegisterController(AdminRegistrationService adminRegistrationService) {
    this.adminRegistrationService = adminRegistrationService;
  }

  @GetMapping("/register")
  public String form(@RequestParam(required = false) String token, Model model) {
    if (token == null || token.isBlank()) {
      return "register-missing-token";
    }
    model.addAttribute("token", token);
    return "register";
  }

  @PostMapping("/register")
  public String submit(
      @RequestParam String token,
      @RequestParam String email,
      @RequestParam String password,
      @RequestParam String confirm,
      Model model) {
    if (!password.equals(confirm)) {
      model.addAttribute("error", "Passwords do not match");
      model.addAttribute("token", token);
      return "register";
    }
    if (password.length() < 8) {
      model.addAttribute("error", "Password must be at least 8 characters");
      model.addAttribute("token", token);
      return "register";
    }
    try {
      adminRegistrationService.register(token, email, password);
    } catch (org.springframework.web.server.ResponseStatusException ex) {
      model.addAttribute("error", ex.getReason());
      model.addAttribute("token", token);
      return "register";
    }
    return "redirect:/login?registered";
  }
}
