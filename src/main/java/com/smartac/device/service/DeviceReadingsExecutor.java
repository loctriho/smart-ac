package com.smartac.device.service;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Executor abstraction for async device readings persistence.
 *
 * <p>We intentionally expose minimal observability (queue + active) so tests and load tools can
 * await drain without depending on a specific executor implementation.
 */
public interface DeviceReadingsExecutor extends Executor {

  int getActiveCount();

  int getQueueSize();

  default void awaitDrained(Duration timeout) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (getActiveCount() == 0 && getQueueSize() == 0) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("deviceReadingsExecutor did not drain within " + timeout);
  }
}

