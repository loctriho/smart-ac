package com.smartac;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.device-ingest.readings-rate-limit-seconds=60")
class DeviceReadingsRateLimitIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void secondReadingsRequestWithinOneMinuteIs429() throws Exception {
    String reg =
        mockMvc
            .perform(
                post("/api/v1/devices/register")
                    .contentType(APPLICATION_JSON)
                    .content(
                        """
                        {"serialNumber":"RL-INT-1","firmwareVersion":"1.0"}
                        """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = objectMapper.readTree(reg).get("apiToken").asText();

    String one =
        """
        {"readings":[{"recordedAt":"2026-04-21T12:00:00Z","temperatureCelsius":21,"humidityPercent":50,"carbonMonoxidePpm":1,"healthStatus":"ok"}]}
        """;

    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(one))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/devices/readings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(one))
        .andExpect(status().isTooManyRequests());
  }
}
