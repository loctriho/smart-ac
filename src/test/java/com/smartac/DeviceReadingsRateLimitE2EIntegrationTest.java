package com.smartac;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Per-device rate limit for {@code POST /readings} is enforced in-process; this class overrides
 * the window to 60s so we can assert HTTP 429 without waiting a full minute in production config.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "app.device-ingest.readings-rate-limit-seconds=60",
      "app.device-ingest.queue-capacity=500",
      "app.device-ingest.worker-threads=4",
    })
class DeviceReadingsRateLimitE2EIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void secondReadingsPostWithin60Seconds_returns429() throws Exception {
    String serial = "RL-" + UUID.randomUUID().toString().substring(0, 8);
    MvcResult reg =
        mockMvc
            .perform(
                post("/api/v1/devices/register")
                    .contentType(APPLICATION_JSON)
                    .content("{\"serialNumber\":\"" + serial + "\",\"firmwareVersion\":\"1.0\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("apiToken").asText();
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(5, ChronoUnit.MINUTES);
    Instant t1 = t0.plus(1, ChronoUnit.MINUTES);
    String twoSamples =
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
                },
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
            t0,
            t1);
    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoSamples))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.acceptedSamples").value(2));

    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(twoSamples))
        .andExpect(status().isTooManyRequests());
  }
}
