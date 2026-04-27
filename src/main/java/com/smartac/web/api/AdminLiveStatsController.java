package com.smartac.web.api;

import com.smartac.admin.service.DashboardStateService;
import com.smartac.web.api.dto.DashboardStateDto;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminLiveStatsController {

  private final DashboardStateService dashboardState;

  public AdminLiveStatsController(DashboardStateService dashboardState) {
    this.dashboardState = dashboardState;
  }

  @GetMapping("/live-stats")
  public Map<String, Long> liveStats() {
    DashboardStateDto d = dashboardState.buildState();
    return Map.of("deviceCount", d.deviceCount(), "openNotificationCount", d.openNotificationCount());
  }

  @GetMapping("/dashboard-state")
  public DashboardStateDto dashboardState(
      @RequestParam(required = false) Long afterId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "100") int size,
      @RequestParam(required = false, defaultValue = "true") boolean includeLatestReadings) {
    if (afterId != null) {
      return dashboardState.buildStateKeyset(afterId, size, includeLatestReadings);
    }
    return dashboardState.buildState(page, size, includeLatestReadings);
  }
}
