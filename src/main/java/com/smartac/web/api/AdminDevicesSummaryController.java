package com.smartac.web.api;

import com.smartac.device.model.Device;
import com.smartac.device.repo.DeviceRepository;
import com.smartac.web.api.dto.DeviceSummaryDto;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/devices")
public class AdminDevicesSummaryController {

  public record DeviceSummaryListResponse(
      List<DeviceSummaryDto> devices, String searchNote, long total, int page, int size) {}

  private final DeviceRepository deviceRepository;

  public AdminDevicesSummaryController(DeviceRepository deviceRepository) {
    this.deviceRepository = deviceRepository;
  }

  @GetMapping("/summary")
  public DeviceSummaryListResponse summary(
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "100") int size,
      @RequestParam(required = false, defaultValue = "true") boolean includeTotal) {
    int safeSize = Math.max(1, Math.min(500, size));
    int safePage = Math.max(0, page);
    int offset = safePage * safeSize;

    if (q != null && !q.isBlank()) {
      Optional<Device> match = deviceRepository.findBySerialNumberIgnoreCase(q.trim());
      if (match.isPresent()) {
        return new DeviceSummaryListResponse(List.of(toDto(match.get())), null, 1, 0, safeSize);
      }
      long total = includeTotal ? deviceRepository.count() : -1;
      List<DeviceSummaryDto> rows =
          deviceRepository.findPage(offset, safeSize).stream().map(this::toDto).toList();
      return new DeviceSummaryListResponse(
          rows, "No exact serial match; showing all devices.", total, safePage, safeSize);
    }

    long total = includeTotal ? deviceRepository.count() : -1;
    List<DeviceSummaryDto> rows =
        deviceRepository.findPage(offset, safeSize).stream().map(this::toDto).toList();
    return new DeviceSummaryListResponse(rows, null, total, safePage, safeSize);
  }

  private DeviceSummaryDto toDto(Device d) {
    return new DeviceSummaryDto(
        d.getId(), d.getSerialNumber(), d.getFirmwareVersion(), d.isEnabled(), d.getRegistrationDate());
  }
}
