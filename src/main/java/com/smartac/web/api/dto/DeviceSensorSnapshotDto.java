package com.smartac.web.api.dto;

public record DeviceSensorSnapshotDto(
    long deviceId, String serialNumber, LastReadingDto lastReading) {}
