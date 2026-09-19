package org.example.leaderboardservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public final class LeaderboardDtos {
    private LeaderboardDtos() {}

    public static final long MAX_SCORE = 9_007_199_254_740_991L;
    public static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";
    public enum Order { asc, desc }
    public record SetScore(@NotNull @Min(-MAX_SCORE) @Max(MAX_SCORE) Long score) {}
    public record AdjustScore(@NotNull @Min(-MAX_SCORE) @Max(MAX_SCORE) Long delta,
                              @NotNull @Pattern(regexp = ID_PATTERN) String requestId) {}
    public record ScoreResult(String playerId, long score) {}
    public record AdjustmentResult(String playerId, long score, boolean replayed) {}
    public record Entry(String playerId, long score, long rank) {}
    public record Page(long offset, int limit, Order order, List<Entry> entries) {}
    public record Neighbors(String playerId, int radius, Order order, List<Entry> entries) {}
    public record Stats(long participantCount) {}
}
