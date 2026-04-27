package com.smartac.device.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulkReadingsResponse(int acceptedSamples, Boolean queued) {

  public static BulkReadingsResponse sync(int acceptedSamples) {
    return new BulkReadingsResponse(acceptedSamples, null);
  }

  public static BulkReadingsResponse async(int acceptedSamples) {
    return new BulkReadingsResponse(acceptedSamples, true);
  }
}
