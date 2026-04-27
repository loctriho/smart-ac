package com.smartac.device.api.dto;

import org.springframework.http.HttpStatus;

public record BulkReadingsIngestResult(BulkReadingsResponse body, HttpStatus status) {}
