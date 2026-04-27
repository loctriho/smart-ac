package com.smartac.device.service;

/** Thrown when the ingest executor rejects new background work (HTTP 503). */
public class ServiceOverloadedException extends RuntimeException {

  public ServiceOverloadedException(String message) {
    super(message);
  }

  public ServiceOverloadedException(String message, Throwable cause) {
    super(message, cause);
  }
}
