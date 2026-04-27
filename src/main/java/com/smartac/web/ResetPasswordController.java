package com.smartac.web;

import com.smartac.admin.service.PasswordResetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ResetPasswordController {

  private final PasswordResetService passwordResetService;

  public ResetPasswordController(PasswordResetService passwordResetService) {
    this.passwordResetService = passwordResetService;
  }

  @GetMapping("/reset-password")
  public String form(@RequestParam String token, Model model) {
    model.addAttribute("token", token);
    return "reset-password";
  }

  @PostMapping("/reset-password")
  public String submit(
      @RequestParam String token, @RequestParam String password, @RequestParam String confirm, Model model) {
    if (!password.equals(confirm)) {
      model.addAttribute("error", "Passwords do not match");
      model.addAttribute("token", token);
      return "reset-password";
    }
    if (password.length() < 8) {
      model.addAttribute("error", "Password must be at least 8 characters");
      model.addAttribute("token", token);
      return "reset-password";
    }
    try {
      passwordResetService.resetPassword(token, password);
    } catch (org.springframework.web.server.ResponseStatusException ex) {
      model.addAttribute("error", ex.getReason());
      model.addAttribute("token", token);
      return "reset-password";
    }
    return "redirect:/login?reset";
  }
}
