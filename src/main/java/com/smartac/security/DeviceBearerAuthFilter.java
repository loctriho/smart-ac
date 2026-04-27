package com.smartac.security;

import com.smartac.device.model.Device;
import com.smartac.device.repo.DeviceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Registered only on the device ingest {@link com.smartac.config.SecurityConfig} chain. */
public class DeviceBearerAuthFilter extends OncePerRequestFilter {

  private final DeviceRepository deviceRepository;

  public DeviceBearerAuthFilter(DeviceRepository deviceRepository) {
    this.deviceRepository = deviceRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"Missing or invalid Authorization header (Bearer token required)\"}");
      return;
    }
    String raw = auth.substring(7).trim();
    if (raw.isEmpty()) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"Empty bearer token\"}");
      return;
    }
    String hash = TokenHasher.sha256Hex(raw);
    Device device =
        deviceRepository
            .findByApiTokenHash(hash)
            .orElse(null);
    if (device == null) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"Invalid device token\"}");
      return;
    }
    if (!device.isEnabled()) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"Device is disabled\"}");
      return;
    }
    SecurityContextHolder.getContext().setAuthentication(new DeviceAuthentication(device));
    try {
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
