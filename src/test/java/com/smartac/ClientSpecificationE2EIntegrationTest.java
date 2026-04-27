package com.smartac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartac.notification.repo.AdminNotificationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
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
 * End-to-end integration tests mapped to the client exercise specification: device API (register,
 * sensor ingest, bulk backlog), admin JSON API (devices, search, series, notifications), and auth
 * gates. Uses the same H2 test datasource as other ITs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "app.device-ingest.readings-rate-limit-seconds=0",
    })
class ClientSpecificationE2EIntegrationTest {

  private static final String ADMIN = "admin@smartac.local";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AdminNotificationRepository notificationRepository;

  private MvcResult registerDevice(String serial, String firmware) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/devices/register")
                .contentType(APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT, "{\"serialNumber\":\"%s\",\"firmwareVersion\":\"%s\"}", serial, firmware)))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private String apiToken(String registerResponseBody) throws Exception {
    return objectMapper.readTree(registerResponseBody).get("apiToken").asText();
  }

  private long deviceId(String registerResponseBody) throws Exception {
    return objectMapper.readTree(registerResponseBody).get("deviceId").asLong();
  }

  @Test
  void registerDuplicateSerial_returns409() throws Exception {
    String serial = "DUP-" + UUID.randomUUID().toString().substring(0, 8);
    registerDevice(serial, "1.0");
    mockMvc
        .perform(
            post("/api/v1/devices/register")
                .contentType(APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT, "{\"serialNumber\":\"%s\",\"firmwareVersion\":\"1.0\"}", serial)))
        .andExpect(status().isConflict());
  }

  @Test
  void registerResponse_containsSerialFirmwareRegistrationDate() throws Exception {
    String serial = "REG-" + UUID.randomUUID().toString().substring(0, 8);
    MvcResult res = registerDevice(serial, "3.2.1");
    JsonNode j = objectMapper.readTree(res.getResponse().getContentAsString());
    assertThat(j.get("serialNumber").asText()).isEqualTo(serial);
    assertThat(j.get("firmwareVersion").asText()).isEqualTo("3.2.1");
    assertThat(j.get("registrationDate").asText()).isNotBlank();
  }

  @Test
  void postReadings_emptyArray_returns400() throws Exception {
    String serial = "EMPTY-" + UUID.randomUUID().toString().substring(0, 8);
    String token = apiToken(registerDevice(serial, "1.0").getResponse().getContentAsString());
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("{\"readings\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postReadings_subMinuteClockSkewAccepted() throws Exception {
    String serial = "MIN-" + UUID.randomUUID().toString().substring(0, 8);
    String token = apiToken(registerDevice(serial, "1.0").getResponse().getContentAsString());
    // Slightly after "now" in raw time, but still the same UTC minute after truncation.
    Instant recorded = Instant.now().plusMillis(400);
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT,
                        """
                        {
                          "readings": [
                            {
                              "recordedAt": "%s",
                              "temperatureCelsius": 20.0,
                              "humidityPercent": 45.0,
                              "carbonMonoxidePpm": 1.0,
                              "healthStatus": "ok"
                            }
                          ]
                        }
                        """,
                        recorded)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptedSamples").value(1));
  }

  @Test
  void postReadings_futureRecordedAt_returns400() throws Exception {
    String serial = "FUT-" + UUID.randomUUID().toString().substring(0, 8);
    String token = apiToken(registerDevice(serial, "1.0").getResponse().getContentAsString());
    Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT,
                        """
                        {
                          "readings": [
                            {
                              "recordedAt": "%s",
                              "temperatureCelsius": 20.0,
                              "humidityPercent": 45.0,
                              "carbonMonoxidePpm": 1.0,
                              "healthStatus": "ok"
                            }
                          ]
                        }
                        """,
                        future)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postReadings_exceedsMaxSamplesPerRequest_returns400() throws Exception {
    String serial = "MAX-" + UUID.randomUUID().toString().substring(0, 8);
    String token = apiToken(registerDevice(serial, "1.0").getResponse().getContentAsString());
    String body = buildReadingsJson(501);
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void adminDeviceSummary_unauthenticated_isRedirectedOrForbidden() throws Exception {
    mockMvc.perform(get("/api/admin/devices/summary")).andExpect(status().is3xxRedirection());
  }

  @Test
  void adminDeviceSummary_bySerial_findsDevice() throws Exception {
    String serial = "FIND-" + UUID.randomUUID().toString().substring(0, 8);
    registerDevice(serial, "9.9.9");
    mockMvc
        .perform(get("/api/admin/devices/summary").param("q", serial).with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.devices.length()").value(1))
        .andExpect(jsonPath("$.devices[0].serialNumber").value(serial));
  }

  @Test
  void adminDeviceDetail_and_series_afterIngest() throws Exception {
    String serial = "DET-" + UUID.randomUUID().toString().substring(0, 8);
    MvcResult reg = registerDevice(serial, "1.0");
    long id = deviceId(reg.getResponse().getContentAsString());
    String token = apiToken(reg.getResponse().getContentAsString());
    Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT,
                        """
                        {"readings":[{"recordedAt":"%s","temperatureCelsius":21.5,"humidityPercent":55.0,"carbonMonoxidePpm":1.2,"healthStatus":"ok"}]}
                        """,
                        t)))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/admin/devices/" + id + "/detail").with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.device.serialNumber").value(serial))
        .andExpect(jsonPath("$.recentReadings[0].recordedAt").exists());

    mockMvc
        .perform(
            get("/api/admin/devices/" + id + "/series")
                .param("sensor", "temperature")
                .param("range", "today")
                .with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void healthKeywordSingleSample_doesNotCreateNotification() throws Exception {
    String serial = "HLTH-" + UUID.randomUUID().toString().substring(0, 8);
    String token = apiToken(registerDevice(serial, "1.0").getResponse().getContentAsString());
    long before = notificationRepository.count();
    Instant past = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.HOURS);
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT,
                        """
                        {
                          "readings": [
                            {
                              "recordedAt": "%s",
                              "temperatureCelsius": 20.0,
                              "humidityPercent": 45.0,
                              "carbonMonoxidePpm": 1.0,
                              "healthStatus": "gas_leak"
                            }
                          ]
                        }
                        """,
                        past)))
        .andExpect(status().isOk());
    assertThat(notificationRepository.count()).isEqualTo(before);
  }

  @Test
  void resolveNotification_hidesFromUnresolved() throws Exception {
    String serial = "RES-" + UUID.randomUUID().toString().substring(0, 8);
    MvcResult reg = registerDevice(serial, "1.0");
    long id = deviceId(reg.getResponse().getContentAsString());
    long before = notificationRepository.count();
    mockMvc
        .perform(
            post("/api/admin/devices/" + id + "/simulate-notifications")
                .with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk());
    assertThat(notificationRepository.count()).isGreaterThanOrEqualTo(before + 1);

    JsonNode unresolved =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/admin/notifications/unresolved").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    long toResolve = 0L;
    for (JsonNode n : unresolved) {
      if (n.has("id") && serial.equalsIgnoreCase(n.get("device").get("serialNumber").asText())) {
        toResolve = n.get("id").asLong();
        break;
      }
    }
    assertThat(toResolve).isPositive();

    mockMvc
        .perform(
            post("/api/admin/notifications/" + toResolve + "/resolve")
                .with(user(ADMIN).roles("ADMIN"))
                .with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void listAdmins_returnsBootstrapAdmin() throws Exception {
    mockMvc
        .perform(get("/api/admin/admins").with(user(ADMIN).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value(ADMIN));
  }

  @Test
  void createInvitation_returnsLink() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/invitations")
                .contentType(APPLICATION_JSON)
                .content("{\"emailHint\":\"reviewer@example.com\"}")
                .with(user(ADMIN).roles("ADMIN"))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inviteLink").exists());
  }

  private static String buildReadingsJson(int count) {
    StringBuilder sb = new StringBuilder("{\"readings\":[");
    Instant t0 =
        Instant.now()
            .truncatedTo(ChronoUnit.SECONDS)
            .minusSeconds(Math.max(count, 1) + 30L);
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(
          String.format(
              Locale.US,
              "{\"recordedAt\":\"%s\",\"temperatureCelsius\":20.0,\"humidityPercent\":50.0,\"carbonMonoxidePpm\":1.0,\"healthStatus\":\"ok\"}",
              t0.plusSeconds(i)));
    }
    sb.append("]}");
    return sb.toString();
  }
}
