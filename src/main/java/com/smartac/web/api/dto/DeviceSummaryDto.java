package com.smartac.web.api.dto;

import java.time.Instant;

public record DeviceSummaryDto(
    long id,
    String serialNumber,
    String firmwareVersion,
    boolean enabled,
    Instant registrationDate) {}
