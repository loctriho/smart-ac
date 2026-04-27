package com.smartac.device.service;

/** Thrown when a device exceeds the configured per-device readings request interval. */
public class RateLimitException extends RuntimeException {
  public RateLimitException(String message) {
    super(message);
  }
}
