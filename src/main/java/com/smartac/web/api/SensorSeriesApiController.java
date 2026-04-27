package com.smartac.web.api;

import com.smartac.admin.service.SensorSeriesService;
import com.smartac.admin.service.SensorSeriesService.SensorChannel;
import com.smartac.admin.service.SensorSeriesService.SeriesPoint;
import com.smartac.web.TimeRange;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/devices")
public class SensorSeriesApiController {

  private final SensorSeriesService sensorSeriesService;

  public SensorSeriesApiController(SensorSeriesService sensorSeriesService) {
    this.sensorSeriesService = sensorSeriesService;
  }

  @GetMapping("/{deviceId}/series")
  public List<SeriesPoint> series(
      @PathVariable long deviceId,
      @RequestParam String sensor,
      @RequestParam(defaultValue = "today") String range) {
    SensorChannel ch = SensorChannel.valueOf(sensor.toLowerCase());
    TimeRange tr = TimeRange.valueOf(range.toLowerCase());
    return sensorSeriesService.series(deviceId, ch, tr);
  }
}
