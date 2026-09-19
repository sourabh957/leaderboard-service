package org.example.leaderboardservice.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("leaderboard")
public record LeaderboardProperties(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String keyPrefix,
        @Min(1) @Max(604800) long idempotencyTtlSeconds) {
}
