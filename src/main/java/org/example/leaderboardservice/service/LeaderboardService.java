package org.example.leaderboardservice.service;

import org.example.leaderboardservice.dto.LeaderboardDtos.*;
import org.example.leaderboardservice.exception.LeaderboardException;
import org.example.leaderboardservice.repository.LeaderboardRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LeaderboardService {
    private final LeaderboardRepository repository;
    public LeaderboardService(LeaderboardRepository repository) { this.repository = repository; }

    public ScoreResult set(String game, String board, String player, SetScore request) {
        return repository.set(game, board, player, request.score());
    }
    public AdjustmentResult adjust(String game, String board, String player, AdjustScore request) {
        return repository.adjust(game, board, player, request);
    }
    public Page entries(String game, String board, long offset, int limit, Order order) {
        return new Page(offset, limit, order, repository.read(game, board, "entries", order, Long.toString(offset), Integer.toString(limit)));
    }
    public Entry player(String game, String board, String player, Order order) {
        var entries = repository.read(game, board, "player", order, player, "0");
        if (entries.isEmpty()) throw missing();
        return entries.getFirst();
    }
    public Neighbors neighbors(String game, String board, String player, int radius, Order order) {
        var entries = repository.read(game, board, "neighbors", order, player, Integer.toString(radius));
        if (entries.isEmpty()) throw missing();
        return new Neighbors(player, radius, order, entries);
    }
    public Stats stats(String game, String board) { return new Stats(repository.count(game, board)); }
    public void remove(String game, String board, String player) { repository.remove(game, board, player); }
    private LeaderboardException missing() { return new LeaderboardException(HttpStatus.NOT_FOUND, "Player is not on this leaderboard"); }
}
