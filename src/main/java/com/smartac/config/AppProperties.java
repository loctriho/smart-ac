package com.smartac.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    String baseUrl,
    Bootstrap bootstrap,
    @DefaultValue("72") int invitationExpiryHours,
    @DefaultValue("60") int passwordResetTokenMinutes,
    @DefaultValue("false") boolean devLogPasswordResetLinks) {

  public record Bootstrap(String email, String password) {}
}
