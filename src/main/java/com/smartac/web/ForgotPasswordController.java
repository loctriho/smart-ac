package com.smartac.web;

import com.smartac.admin.service.PasswordResetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ForgotPasswordController {

  private final PasswordResetService passwordResetService;

  public ForgotPasswordController(PasswordResetService passwordResetService) {
    this.passwordResetService = passwordResetService;
  }

  @GetMapping("/forgot-password")
  public String form() {
    return "forgot-password";
  }

  @PostMapping("/forgot-password")
  public String submit(@RequestParam String email, Model model) {
    passwordResetService.requestReset(email);
    model.addAttribute("message", "If an account exists for that email, a reset link has been issued.");
    return "forgot-password-result";
  }
}
