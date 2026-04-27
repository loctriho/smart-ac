package com.smartac.device.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceRegistrationRequest(
    @NotBlank @Size(max = 120) String serialNumber,
    @NotBlank @Size(max = 64) String firmwareVersion) {}
