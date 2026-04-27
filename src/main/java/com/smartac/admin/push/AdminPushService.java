package com.smartac.admin.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AdminPushService {

  private static final Logger log = LoggerFactory.getLogger(AdminPushService.class);
  private static final int CLIENT_QUEUE_CAPACITY = 256;

  private final ObjectMapper objectMapper;
  private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService keepAlive =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "admin-sse-keepalive");
            t.setDaemon(true);
            return t;
          });
  private final ExecutorService sendPool =
      Executors.newFixedThreadPool(
          Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
          r -> {
            Thread t = new Thread(r, "admin-sse-send");
            t.setDaemon(true);
            return t;
          });

  public AdminPushService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    keepAlive.scheduleAtFixedRate(this::pingAll, 20, 20, TimeUnit.SECONDS);
  }

  public SseEmitter openStream() {
    SseEmitter emitter = new SseEmitter(0L);
    Client client = new Client(emitter);
    clients.add(client);
    Runnable remove = () -> clients.remove(client);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(ex -> remove.run());
    try {
      // Send initial hello immediately so the browser considers the connection alive.
      emitter.send(
          SseEmitter.event().data(writeJson(Map.of("topic", "hello")), MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      emitter.complete();
      clients.remove(client);
    }
    return emitter;
  }

  /** After readings are persisted (sync path or queue worker). */
  public void notifyReadingsIngested(long deviceId) {
    broadcast(Map.of("topic", "readings", "deviceId", deviceId));
  }

  /** After notifications are created or resolved. */
  public void notifyNotificationsChanged() {
    broadcast(Map.of("topic", "notifications"));
  }

  /** After a new device is registered (admin dashboards / device lists should refresh). */
  public void notifyDevicesChanged() {
    broadcast(Map.of("topic", "devices"));
  }

  private void broadcast(Map<String, ?> payload) {
    String json = writeJson(payload);
    if (json == null) {
      return;
    }
    for (Client client : clients) {
      client.enqueue(Event.data(json));
    }
  }

  private void pingAll() {
    for (Client client : clients) {
      client.enqueue(Event.ping());
    }
  }

  private String writeJson(Map<String, ?> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize SSE payload", e);
      return null;
    }
  }

  @PreDestroy
  void shutdown() {
    keepAlive.shutdownNow();
    sendPool.shutdownNow();
    for (Client client : clients) {
      try {
        client.emitter.complete();
      } catch (Exception ignored) {
        // ignore
      }
    }
    clients.clear();
  }

  private record Event(String json, boolean keepAlive) {
    static Event data(String json) {
      return new Event(json, false);
    }

    static Event ping() {
      return new Event(null, true);
    }
  }

  /**
   * Per-client bounded queue to apply backpressure. Under load we drop oldest messages to keep the
   * connection responsive (latest updates win).
   */
  private final class Client {
    private final SseEmitter emitter;
    private final ArrayBlockingQueue<Event> q = new ArrayBlockingQueue<>(CLIENT_QUEUE_CAPACITY);
    private final AtomicBoolean draining = new AtomicBoolean(false);

    // Coalesce bursty topics so we don't enqueue duplicates endlessly.
    private final ConcurrentHashMap<String, Boolean> pendingTopics = new ConcurrentHashMap<>();

    private Client(SseEmitter emitter) {
      this.emitter = emitter;
    }

    void enqueue(Event ev) {
      if (ev.keepAlive) {
        offerDroppingOldest(ev);
        scheduleDrain();
        return;
      }

      String topic = extractTopic(ev.json);
      if (topic != null) {
        // If the same topic is already queued/in-flight, skip enqueuing another identical "poke".
        // This keeps the UI lively while bounding work during load tests.
        if (pendingTopics.putIfAbsent(topic, Boolean.TRUE) != null) {
          return;
        }
      }
      offerDroppingOldest(ev);
      scheduleDrain();
    }

    private void offerDroppingOldest(Event ev) {
      if (q.offer(ev)) {
        return;
      }
      // Queue full -> drop oldest, then try again (never block producer threads).
      q.poll();
      q.offer(ev);
    }

    private void scheduleDrain() {
      if (!draining.compareAndSet(false, true)) {
        return;
      }
      sendPool.execute(this::drainLoop);
    }

    private void drainLoop() {
      try {
        while (true) {
          Event ev = q.poll();
          if (ev == null) {
            return;
          }
          try {
            if (ev.keepAlive) {
              emitter.send(SseEmitter.event().comment("keepalive"));
            } else {
              emitter.send(SseEmitter.event().data(ev.json, MediaType.APPLICATION_JSON));
            }
          } catch (Exception sendErr) {
            try {
              emitter.complete();
            } catch (Exception ignored) {
              // ignore
            }
            clients.remove(this);
            return;
          } finally {
            if (!ev.keepAlive) {
              String topic = extractTopic(ev.json);
              if (topic != null) {
                pendingTopics.remove(topic);
              }
            }
          }
        }
      } finally {
        draining.set(false);
        // Race: new events may have arrived after we exited the loop.
        if (!q.isEmpty()) {
          scheduleDrain();
        }
      }
    }

    private String extractTopic(String json) {
      // Fast-and-loose extraction to avoid JSON parsing in hot path.
      // Payloads are tiny and controlled (we only emit our own JSON).
      if (json == null) return null;
      int i = json.indexOf("\"topic\"");
      if (i < 0) return null;
      i = json.indexOf(':', i);
      if (i < 0) return null;
      int q1 = json.indexOf('"', i);
      if (q1 < 0) return null;
      int q2 = json.indexOf('"', q1 + 1);
      if (q2 < 0) return null;
      return json.substring(q1 + 1, q2);
    }
  }
}
