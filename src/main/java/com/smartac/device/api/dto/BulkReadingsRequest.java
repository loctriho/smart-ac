package com.smartac.device.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BulkReadingsRequest(
    @NotEmpty @Size(max = 500) @Valid List<SensorReadingPayload> readings) {}
