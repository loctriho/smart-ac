package com.smartac.device.api;

import com.smartac.device.api.dto.BulkReadingsIngestResult;
import com.smartac.device.api.dto.BulkReadingsRequest;
import com.smartac.device.api.dto.BulkReadingsResponse;
import com.smartac.device.api.dto.DeviceRegistrationRequest;
import com.smartac.device.api.dto.DeviceRegistrationResponse;
import com.smartac.device.model.Device;
import com.smartac.device.service.DeviceRegistrationService;
import com.smartac.device.service.ReadingIngestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceApiController {

  private final DeviceRegistrationService registrationService;
  private final ReadingIngestService readingIngestService;

  public DeviceApiController(
      DeviceRegistrationService registrationService, ReadingIngestService readingIngestService) {
    this.registrationService = registrationService;
    this.readingIngestService = readingIngestService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public DeviceRegistrationResponse register(@Valid @RequestBody DeviceRegistrationRequest body) {
    return registrationService.register(body);
  }

  @PostMapping("/readings")
  public ResponseEntity<BulkReadingsResponse> readings(
      @AuthenticationPrincipal Device device,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody BulkReadingsRequest body) {
    if (device == null) {
      throw new IllegalStateException("Device principal required");
    }
    BulkReadingsIngestResult result =
        readingIngestService.ingest(device.getId(), body, idempotencyKey);
    return ResponseEntity.status(result.status()).body(result.body());
  }
}
