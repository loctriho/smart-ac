package com.smartac.config;

import com.smartac.device.service.DeviceReadingsExecutor;
import com.smartac.device.service.ServiceOverloadedException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;

/**
 * Bounded executor backed by Java 21 virtual threads.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>Bounded queue: when full, {@link #execute(Runnable)} throws {@link ServiceOverloadedException}
 *       so HTTP returns 503.
 *   <li>Max in-flight tasks: capped by a semaphore (configured by workerThreads).
 *   <li>Tasks run on virtual threads (cheap blocking), so DB waits don't consume platform threads.
 * </ul>
 */
final class BoundedVirtualThreadExecutor implements DeviceReadingsExecutor, DisposableBean {

  private final BlockingQueue<Runnable> queue;
  private final Semaphore inFlightPermits;
  private final ExecutorService vthreadExecutor;
  private final Thread dispatcher;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicInteger active = new AtomicInteger(0);

  BoundedVirtualThreadExecutor(int maxInFlight, int queueCapacity, String threadNamePrefix) {
    if (maxInFlight < 1) {
      throw new IllegalArgumentException("maxInFlight must be >= 1");
    }
    if (queueCapacity < 1) {
      throw new IllegalArgumentException("queueCapacity must be >= 1");
    }
    Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");

    this.queue = new ArrayBlockingQueue<>(queueCapacity);
    this.inFlightPermits = new Semaphore(maxInFlight);

    ThreadFactory vf = Thread.ofVirtual().name(threadNamePrefix + "v-", 0).factory();
    this.vthreadExecutor = Executors.newThreadPerTaskExecutor(vf);

    this.dispatcher =
        Thread.ofPlatform()
            .name(threadNamePrefix + "dispatcher")
            .daemon(true)
            .unstarted(this::dispatchLoop);
    this.dispatcher.start();
  }

  @Override
  public void execute(Runnable command) {
    if (!running.get()) {
      throw new RejectedExecutionException("executor is shut down");
    }
    boolean offered = queue.offer(command);
    if (!offered) {
      throw new ServiceOverloadedException("Ingest executor queue is full; retry with backoff.");
    }
  }

  private void dispatchLoop() {
    while (running.get()) {
      try {
        Runnable r = queue.poll(250, TimeUnit.MILLISECONDS);
        if (r == null) {
          continue;
        }
        inFlightPermits.acquire();
        active.incrementAndGet();
        vthreadExecutor.execute(
            () -> {
              try {
                r.run();
              } finally {
                active.decrementAndGet();
                inFlightPermits.release();
              }
            });
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException ex) {
        // Keep dispatcher alive; tasks already queued will continue.
      }
    }
  }

  @Override
  public int getActiveCount() {
    return active.get();
  }

  @Override
  public int getQueueSize() {
    return queue.size();
  }

  @Override
  public void destroy() {
    running.set(false);
    dispatcher.interrupt();
    vthreadExecutor.shutdown();
    try {
      vthreadExecutor.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    vthreadExecutor.shutdownNow();
  }
}

