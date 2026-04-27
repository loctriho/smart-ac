package com.smartac.web.api;

import com.smartac.device.model.Device;
import com.smartac.device.model.SensorReading;
import com.smartac.device.repo.DeviceRepository;
import com.smartac.device.repo.SensorReadingRepository;
import com.smartac.web.api.dto.DeviceDetailDto;
import com.smartac.web.api.dto.DeviceSummaryDto;
import com.smartac.web.api.dto.LastReadingDto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/devices")
public class AdminDeviceDetailApiController {

  private final DeviceRepository deviceRepository;
  private final SensorReadingRepository sensorReadingRepository;

  public AdminDeviceDetailApiController(
      DeviceRepository deviceRepository, SensorReadingRepository sensorReadingRepository) {
    this.deviceRepository = deviceRepository;
    this.sensorReadingRepository = sensorReadingRepository;
  }

  @GetMapping("/{deviceId}/detail")
  public DeviceDetailDto detail(@PathVariable long deviceId) {
    Device device =
        deviceRepository
            .findById(deviceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    List<SensorReading> recent = sensorReadingRepository.findRecentByDevice(device, 200);
    DeviceSummaryDto summary =
        new DeviceSummaryDto(
            device.getId(),
            device.getSerialNumber(),
            device.getFirmwareVersion(),
            device.isEnabled(),
            device.getRegistrationDate());
    List<LastReadingDto> rows = recent.stream().map(LastReadingDto::from).toList();
    return new DeviceDetailDto(summary, rows);
  }
}
