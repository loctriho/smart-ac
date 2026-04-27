package com.smartac.web.api.dto;

import java.util.List;

public record DashboardStateDto(
    long deviceCount, long openNotificationCount, List<DeviceSensorSnapshotDto> deviceSnapshots) {}
