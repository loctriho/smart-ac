package com.smartac.device.api.dto;

import java.time.Instant;

public record DeviceRegistrationResponse(
    long deviceId,
    String serialNumber,
    Instant registrationDate,
    String firmwareVersion,
    String apiToken) {}
