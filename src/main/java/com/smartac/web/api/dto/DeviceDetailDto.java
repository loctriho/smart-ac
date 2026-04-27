package com.smartac.web.api.dto;

import java.util.List;

public record DeviceDetailDto(DeviceSummaryDto device, List<LastReadingDto> recentReadings) {}
