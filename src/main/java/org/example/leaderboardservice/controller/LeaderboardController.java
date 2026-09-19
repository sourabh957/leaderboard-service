package org.example.leaderboardservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.example.leaderboardservice.dto.LeaderboardDtos.*;
import org.example.leaderboardservice.service.LeaderboardService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static org.example.leaderboardservice.dto.LeaderboardDtos.ID_PATTERN;

@RestController
@RequestMapping("/api/v1/games/{gameId}/leaderboards/{leaderboardId}")
public class LeaderboardController {
    private final LeaderboardService service;
    public LeaderboardController(LeaderboardService service) { this.service = service; }

    @PutMapping("/players/{playerId}/score")
    public ScoreResult set(@PathVariable @Pattern(regexp = ID_PATTERN) String gameId,
                           @PathVariable @Pattern(regexp = ID_PATTERN) String leaderboardId,
                           @PathVariable @Pattern(regexp = ID_PATTERN) String playerId,
                           @RequestBody @Valid SetScore request) {
        return service.set(gameId, leaderboardId, playerId, request);
    }
    @PostMapping("/players/{playerId}/score-adjustments")
    public AdjustmentResult adjust(@PathVariable @Pattern(regexp = ID_PATTERN) String gameId,
                                   @PathVariable @Pattern(regexp = ID_PATTERN) String leaderboardId,
                                   @PathVariable @Pattern(regexp = ID_PATTERN) String playerId,
                                   @RequestBody @Valid AdjustScore request) {
        return service.adjust(gameId, leaderboardId, playerId, request);
    }
    @GetMapping("/entries")
    public Page entries(@PathVariable @Pattern(regexp = ID_PATTERN) String gameId,
                        @PathVariable @Pattern(regexp = ID_PATTERN) String leaderboardId,
                        @RequestParam(defaultValue = "0") @Min(0) @Max(2147483647) long offset,
                        @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
                        @RequestParam(defaultValue = "desc") Order order) {
        return service.entries(gameId, leaderboardId, offset, limit, order);
    }
    @GetMapping("/players/{playerId}")
    public Entry player(@PathVariable @Pattern(regexp = ID_PATTERN) String gameId,
                        @PathVariable @Pattern(regexp = ID_PATTERN) String leaderboardId,
                        @PathVariable @Pattern(regexp = ID_PATTERN) String playerId,
                        @RequestParam(defaultValue = "desc") Order order) {
        return service.player(gameId, leaderboardId, playerId, order);
    }
    @GetMapping("/players/{playerId}/neighbors")
    public Neighbors neighbors(@PathVariable @Pattern(regexp = ID_PATTERN) String gameId,
                               @PathVariable @Pattern(regexp = ID_PATTERN) String leaderboardId,
                               @PathVariable @Pattern(regexp = ID_PATTERN) String playerId,
                               @RequestParam(defaultValue = "3") @Min(0) @Max(25) int radius,
                               @RequestParam(defaultValue = "desc") Order order) {
        return service.neighbors(gameId, leaderboardId, playerId, radius, order);
    }
    @GetMapping("/stats")
    public Stats stats(@PathVariable @Pattern(regexp = ID_PATTERN) String gameId,
                       @PathVariable @Pattern(regexp = ID_PATTERN) String leaderboardId) {
        return service.stats(gameId, leaderboardId);
    }
    @DeleteMapping("/players/{playerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable @Pattern(regexp = ID_PATTERN) String gameId,
                       @PathVariable @Pattern(regexp = ID_PATTERN) String leaderboardId,
                       @PathVariable @Pattern(regexp = ID_PATTERN) String playerId) {
        service.remove(gameId, leaderboardId, playerId);
    }
}
