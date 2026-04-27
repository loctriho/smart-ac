package com.smartac.web.api;

import com.smartac.admin.push.AdminPushService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/admin")
public class AdminStreamController {

  private final AdminPushService adminPushService;

  public AdminStreamController(AdminPushService adminPushService) {
    this.adminPushService = adminPushService;
  }

  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return adminPushService.openStream();
  }
}
