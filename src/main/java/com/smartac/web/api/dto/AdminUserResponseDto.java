package com.smartac.web.api.dto;

import java.time.Instant;

public record AdminUserResponseDto(long id, String email, boolean enabled, boolean blocked, Instant createdAt) {}
