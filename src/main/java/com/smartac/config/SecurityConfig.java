package com.smartac.config;

import com.smartac.device.repo.DeviceRepository;
import com.smartac.security.DeviceBearerAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  /** Device ingest API: bearer token, no session, no CSRF. */
  @Bean
  @Order(1)
  public SecurityFilterChain deviceIngestChain(HttpSecurity http, DeviceRepository deviceRepository)
      throws Exception {
    // Do not register DeviceBearerAuthFilter as its own @Bean — Boot would apply it to every request.
    DeviceBearerAuthFilter deviceBearerAuthFilter = new DeviceBearerAuthFilter(deviceRepository);
    return http.securityMatcher("/api/v1/devices/readings")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .addFilterBefore(deviceBearerAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /** Web UI + device registration + admin JSON API. */
  @Bean
  @Order(2)
  public SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/webjars/**",
                        "/favicon.ico",
                        "/error",
                        "/login",
                        "/register",
                        "/forgot-password",
                        "/reset-password")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/devices/register")
                    .permitAll()
                    .requestMatchers("/admin/**", "/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .csrf(
            csrf ->
                csrf.ignoringRequestMatchers("/api/admin/**", "/api/v1/devices/register"))
        .headers(h -> h.frameOptions(f -> f.sameOrigin()))
        .formLogin(
            f ->
                f.loginPage("/login")
                    .defaultSuccessUrl("/admin/", true)
                    .permitAll())
        .logout(
            l ->
                l.logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .permitAll())
        .build();
  }
}
