package com.smartac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartac.notification.repo.AdminNotificationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack integration tests: Spring context, device HTTP API, and admin HTTP API (including SPA
 * routes and notification simulation). Uses in-memory H2 from {@code
 * src/test/resources/application.properties} unless you activate another profile (for example {@code
 * mysql-test}).
 *
 * <p>No test-managed transaction rollback: each test commits through the application stack (same as
 * production). Re-run individual methods that use fixed serial numbers only after a fresh context or
 * database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      // Many readings POSTs per device in {@link #registerFiveDevicesThenBulkIngestTenThenFiveHundredEach}.
      "app.device-ingest.readings-rate-limit-seconds=0"
    })
class SmartAcEndToEndIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AdminNotificationRepository notificationRepository;

  private static final String ADMIN = "admin@smartac.local";

  /** Max samples per bulk request (matches {@code app.device-ingest.max-samples-per-request}). */
  private static final int MAX_SAMPLES_PER_BULK = 500;

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /**
   * Builds ingest JSON with sinusoidal variation per field so admin charts show distinct waves.
   * CO and health stay in safe bands (CO well under 9 PPM; health {@code ok}).
   */
  private static String bulkReadingsJson(
      Instant base, int count, int minuteOffsetSeed, int deviceIndex) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"readings\":[");
    double phase = deviceIndex * 1.17;
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      Instant t = base.plus(minuteOffsetSeed + i, ChronoUnit.MINUTES);
      double tempC = 20.5 + 3.8 * Math.sin(i * 0.11 + phase);
      double humPct = 50.0 + 16.0 * Math.cos(i * 0.095 + phase * 1.3);
      double coPpm = 1.2 + 2.5 * (0.5 + 0.5 * Math.sin(i * 0.065 + phase * 0.4));
      tempC = clamp(tempC, 18.0, 26.0);
      humPct = clamp(humPct, 32.0, 68.0);
      coPpm = clamp(coPpm, 0.6, 8.0);
      sb.append(
          String.format(
              Locale.US,
              "{\"recordedAt\":\"%s\",\"temperatureCelsius\":%.3f,\"humidityPercent\":%.3f,"
                  + "\"carbonMonoxidePpm\":%.3f,\"healthStatus\":\"ok\"}",
              t, tempC, humPct, coPpm));
    }
    sb.append("]}");
    return sb.toString();
  }

  private String registerDevice(String serial) throws Exception {
    MvcResult reg =
        mockMvc
            .perform(
                post("/api/v1/devices/register")
                    .contentType(APPLICATION_JSON)
                    .content(
                        "{\"serialNumber\":\"" + serial + "\",\"firmwareVersion\":\"e2e-bulk-1.0\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(reg.getResponse().getContentAsString()).get("apiToken").asText();
  }

  private void postBulkReadings(String token, String body, int expectedAccepted) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.acceptedSamples").value(expectedAccepted));
  }

  /**
   * Registers five devices, posts a bulk of 10 benign readings per device, then a bulk of 500 per
   * device (API max). Values stay in normal ranges to avoid thousands of admin notifications.
   */
  @Test
  void registerFiveDevicesThenBulkIngestTenThenFiveHundredEach() throws Exception {
    String runId = UUID.randomUUID().toString().substring(0, 8);
    List<String> tokens = new ArrayList<>(5);
    for (int d = 0; d < 5; d++) {
      String serial = "E2E-5X500-" + runId + "-" + d;
      tokens.add(registerDevice(serial));
    }

    // Anchor far enough in the past that minute offsets (seed + up to 500 samples) never exceed "now".
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(30, ChronoUnit.DAYS);
    for (int d = 0; d < 5; d++) {
      int seed = d * 620;
      postBulkReadings(tokens.get(d), bulkReadingsJson(base, 10, seed, d), 10);
      postBulkReadings(
          tokens.get(d),
          bulkReadingsJson(base, MAX_SAMPLES_PER_BULK, seed + 100, d),
          MAX_SAMPLES_PER_BULK);
    }
  }

  @Test
  void contextLoads() {}

  @Test
  void adminSpaRequiresAuth() throws Exception {
    mockMvc.perform(get("/admin/")).andExpect(status().is3xxRedirection());
  }

  @Test
  void adminSpaIndexOkForAdmin() throws Exception {
    mockMvc
        .perform(get("/admin/").with(user(ADMIN).roles("ADMIN")).with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void adminSpaClientRouteOkForAdmin() throws Exception {
    mockMvc.perform(get("/admin/devices").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
  }

  @Test
  void dashboardStateJsonOk() throws Exception {
    mockMvc
        .perform(get("/api/admin/dashboard-state").with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deviceCount").exists())
        .andExpect(jsonPath("$.openNotificationCount").exists())
        .andExpect(jsonPath("$.deviceSnapshots").isArray());
  }

  @Test
  void adminSseStreamStarts() throws Exception {
    mockMvc
        .perform(get("/api/admin/stream").with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted())
        .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
  }

  @Test
  void adminSimulateNotificationsUnknownDeviceReturns404() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/devices/999999999/simulate-notifications")
                .with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isNotFound());
  }

  /**
   * {@code POST /api/admin/devices/{id}/simulate-notifications} persists readings that trigger the
   * same CO-over-9-PPM rule as production ingest. Notifications are left open (no resolve) so the
   * database keeps them for manual inspection.
   */
  @Test
  void adminSimulateNotificationsCreatesAlerts() throws Exception {
    String serial = "ADM-SIM-" + UUID.randomUUID().toString().substring(0, 8);
    MvcResult reg =
        mockMvc
            .perform(
                post("/api/v1/devices/register")
                    .contentType(APPLICATION_JSON)
                    .content("{\"serialNumber\":\"" + serial + "\",\"firmwareVersion\":\"1.0\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    long deviceId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("deviceId").asLong();

    long openBefore =
        objectMapper
            .readTree(
                mockMvc
                    .perform(get("/api/admin/notifications/open-count").with(user(ADMIN).roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("count")
            .asLong();

    mockMvc
        .perform(
            post("/api/admin/devices/" + deviceId + "/simulate-notifications")
                .with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ingestedSamples").value(4))
        .andExpect(jsonPath("$.message").exists());

    long openAfterSim =
        objectMapper
            .readTree(
                mockMvc
                    .perform(get("/api/admin/notifications/open-count").with(user(ADMIN).roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("count")
            .asLong();
    assertThat(openAfterSim).isGreaterThanOrEqualTo(openBefore + 1);

    JsonNode unresolved =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/admin/notifications/unresolved").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    Set<String> typesSeen = new HashSet<>();
    int rowsForDevice = 0;
    for (JsonNode n : unresolved) {
      if (!n.has("device") || !n.get("device").has("serialNumber")) {
        continue;
      }
      if (!serial.equalsIgnoreCase(n.get("device").get("serialNumber").asText())) {
        continue;
      }
      rowsForDevice++;
      typesSeen.add(n.get("type").asText());
    }
    assertThat(rowsForDevice).isEqualTo(1);
    assertThat(typesSeen).containsExactly("CO_THRESHOLD");
  }

  @Test
  void registerReturnsToken() throws Exception {
    String serial = "INT-TEST-1-" + UUID.randomUUID().toString().substring(0, 8);
    MvcResult res =
        mockMvc
            .perform(
                post("/api/v1/devices/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"serialNumber\":\"" + serial + "\",\"firmwareVersion\":\"2.0.1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.apiToken").exists())
            .andExpect(jsonPath("$.serialNumber").value(serial))
            .andReturn();
    JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
    assertThat(json.get("apiToken").asText()).startsWith("sac_");
  }

  @Test
  void ingestBulkCreatesCoNotification() throws Exception {
    String serial = "INT-TEST-CO-" + UUID.randomUUID().toString().substring(0, 8);
    String reg =
        mockMvc
            .perform(
                post("/api/v1/devices/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"serialNumber\":\"" + serial + "\",\"firmwareVersion\":\"1.0\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = objectMapper.readTree(reg).get("apiToken").asText();

    long before = notificationRepository.count();

    Instant pastCo =
        Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS);
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT,
                        """
                        {
                          "readings": [
                            {
                              "recordedAt": "%s",
                              "temperatureCelsius": 22.0,
                              "humidityPercent": 40.0,
                              "carbonMonoxidePpm": 10.5,
                              "healthStatus": "ok"
                            }
                          ]
                        }
                        """,
                        pastCo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptedSamples").value(1));

    assertThat(notificationRepository.count()).isEqualTo(before + 1);
  }

}
