package org.example.leaderboardservice.repository;

import org.example.leaderboardservice.config.LeaderboardProperties;
import org.example.leaderboardservice.dto.LeaderboardDtos.*;
import org.example.leaderboardservice.exception.LeaderboardException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static org.example.leaderboardservice.dto.LeaderboardDtos.MAX_SCORE;

@Repository
public class LeaderboardRepository {
    private final StringRedisTemplate redis;
    private final LeaderboardProperties properties;
    private static final DefaultRedisScript<List> ADJUST = script("adjust");
    private static final DefaultRedisScript<List> READ = script("read");

    public LeaderboardRepository(StringRedisTemplate redis, LeaderboardProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    private static DefaultRedisScript<List> script(String name) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
        script.setResultType(List.class);
        return script;
    }

    private String base(String game, String board) {
        return properties.keyPrefix() + ":{" + game + ":" + board + "}";
    }

    public ScoreResult set(String game, String board, String player, long score) {
        redis.opsForZSet().add(base(game, board) + ":scores", player, score);
        return new ScoreResult(player, score);
    }

    public AdjustmentResult adjust(String game, String board, String player, AdjustScore request) {
        String base = base(game, board);
        List<?> result = Objects.requireNonNull(redis.execute(ADJUST,
                List.of(base + ":scores", base + ":request:" + request.requestId()),
                player, request.delta().toString(), Long.toString(properties.idempotencyTtlSeconds()), Long.toString(MAX_SCORE)));
        return switch (result.getFirst().toString()) {
            case "CONFLICT" -> throw new LeaderboardException(HttpStatus.CONFLICT, "Request ID already used with a different player or delta");
            case "OUT_OF_RANGE" -> throw new LeaderboardException(HttpStatus.BAD_REQUEST, "Resulting score exceeds the supported integer range");
            case "OK" -> new AdjustmentResult(player, score(result.get(1)), Boolean.parseBoolean(result.get(2).toString()));
            default -> throw new IllegalStateException("Unexpected Redis script result");
        };
    }

    public List<Entry> read(String game, String board, String operation, Order order, String argument, String size) {
        List<?> rows = Objects.requireNonNull(redis.execute(READ, List.of(base(game, board) + ":scores"),
                operation, order.name(), argument, size));
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += 3) {
            entries.add(new Entry(rows.get(i).toString(), score(rows.get(i + 1)), Long.parseLong(rows.get(i + 2).toString())));
        }
        return List.copyOf(entries);
    }

    private static long score(Object value) {
        // Redis can serialize doubles using scientific notation; all accepted integers are exact.
        return (long) Double.parseDouble(value.toString());
    }

    public long count(String game, String board) {
        return Objects.requireNonNull(redis.opsForZSet().zCard(base(game, board) + ":scores"));
    }

    public void remove(String game, String board, String player) {
        redis.opsForZSet().remove(base(game, board) + ":scores", player);
    }
}
