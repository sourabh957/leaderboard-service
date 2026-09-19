package org.example.leaderboardservice;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.example.leaderboardservice.dto.LeaderboardDtos.MAX_SCORE;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaderboardApiIT {
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);
    static { REDIS.start(); }
    @DynamicPropertySource static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.database", () -> "0");
        registry.add("leaderboard.key-prefix", () -> "leaderboard-it");
    }
    @AfterAll static void stopRedis() { REDIS.stop(); }
    @Value("${local.server.port}") int port;
    @Autowired StringRedisTemplate redis;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final JsonMapper mapper = JsonMapper.builder().build();
    String game;
    String base;
    @BeforeEach void uniqueBoard() {
        game = "game-" + UUID.randomUUID();
        base = "/api/v1/games/" + game + "/leaderboards/season-1";
    }
    HttpResponse<String> call(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return mapper.readTree(response.body());
    }
    void set(String player, long score) throws Exception {
        json(call("PUT", base + "/players/" + player + "/score", "{\"score\":" + score + "}"), 200);
    }
    HttpResponse<String> adjust(String player, long delta, String id) throws Exception {
        return call("POST", base + "/players/" + player + "/score-adjustments",
                "{\"delta\":" + delta + ",\"requestId\":\"" + id + "\"}");
    }
    @Test void fullLifecycleAndOrdering() throws Exception {
        set("alice", 100); set("bob", 200); set("carol", 150);
        var page = json(call("GET", base + "/entries", null), 200);
        assertThat(page.at("/entries/0/playerId").asText()).isEqualTo("bob");
        assertThat(page.at("/entries/0/rank").asLong()).isEqualTo(1);
        var rank = json(call("GET", base + "/players/alice", null), 200);
        assertThat(rank.path("rank").asLong()).isEqualTo(3);
        assertThat(json(adjust("alice", 150, "match-1"), 200).path("score").asLong()).isEqualTo(250);
        assertThat(json(call("GET", base + "/players/alice", null), 200).path("rank").asLong()).isEqualTo(1);
        assertThat(json(call("GET", base + "/stats", null), 200).path("participantCount").asLong()).isEqualTo(3);
        assertThat(call("DELETE", base + "/players/alice", null).statusCode()).isEqualTo(204);
        assertThat(call("DELETE", base + "/players/alice", null).statusCode()).isEqualTo(204);
        json(call("GET", base + "/players/alice", null), 404);
        assertThat(json(call("GET", base + "/stats", null), 200).path("participantCount").asLong()).isEqualTo(2);
    }
    @Test void tiesAscendingPaginationAndNeighbors() throws Exception {
        set("alice", 10); set("bob", 10); set("carol", 20); set("dave", -10);
        var desc = json(call("GET", base + "/entries?offset=1&limit=2", null), 200);
        assertThat(desc.at("/entries/0/playerId").asText()).isEqualTo("bob");
        assertThat(desc.at("/entries/0/rank").asLong()).isEqualTo(2);
        assertThat(desc.at("/entries/1/playerId").asText()).isEqualTo("alice");
        var asc = json(call("GET", base + "/entries?order=asc", null), 200);
        assertThat(asc.at("/entries/0/playerId").asText()).isEqualTo("dave");
        assertThat(asc.at("/entries/1/playerId").asText()).isEqualTo("alice");
        var neighbors = json(call("GET", base + "/players/bob/neighbors?radius=1", null), 200);
        assertThat(neighbors.path("entries").size()).isEqualTo(3);
        assertThat(neighbors.at("/entries/0/playerId").asText()).isEqualTo("carol");
        assertThat(json(call("GET", base + "/players/carol/neighbors?radius=1", null), 200).path("entries").size()).isEqualTo(2);
        assertThat(json(call("GET", base + "/players/bob/neighbors?radius=0&order=asc", null), 200).at("/entries/0/rank").asLong()).isEqualTo(3);
    }
    @Test void emptyBoardsAndNamespaceIsolation() throws Exception {
        assertThat(json(call("GET", base + "/entries", null), 200).path("entries").size()).isZero();
        json(call("GET", base + "/players/missing/neighbors", null), 404);
        set("alice", 20);
        assertThat(json(call("GET", base.replace("season-1", "season-2") + "/stats", null), 200).path("participantCount").asLong()).isZero();
        assertThat(json(call("GET", base.replace(game, "other-game") + "/stats", null), 200).path("participantCount").asLong()).isZero();
        assertThat(json(call("GET", base + "/entries?offset=100", null), 200).path("entries").size()).isZero();
    }
    @Test void idempotencyReplaysOriginalResultAndRejectsConflicts() throws Exception {
        assertThat(json(adjust("alice", 50, "match-1"), 200).path("replayed").asBoolean()).isFalse();
        set("alice", 500);
        var replay = json(adjust("alice", 50, "match-1"), 200);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("score").asLong()).isEqualTo(50);
        assertThat(json(call("GET", base + "/players/alice", null), 200).path("score").asLong()).isEqualTo(500);
        json(adjust("alice", 51, "match-1"), 409);
        json(adjust("bob", 50, "match-1"), 409);
        assertThat(redis.getExpire("leaderboard-it:{" + game + ":season-1}:request:match-1")).isBetween(1L, 86400L);
        call("DELETE", base + "/players/alice", null);
        json(adjust("alice", 50, "match-1"), 200);
        json(call("GET", base + "/players/alice", null), 404);
    }
    @Test void exactIntegerBoundariesAndOverflowDoNotMutate() throws Exception {
        set("max", MAX_SCORE); set("min", -MAX_SCORE);
        assertThat(json(call("GET", base + "/players/max", null), 200).path("score").asLong()).isEqualTo(MAX_SCORE);
        json(adjust("max", 1, "overflow"), 400);
        json(adjust("min", -1, "underflow"), 400);
        assertThat(json(adjust("max", -1, "safe"), 200).path("score").asLong()).isEqualTo(MAX_SCORE - 1);
        assertThat(json(adjust("min", 1, "safe-min"), 200).path("score").asLong()).isEqualTo(-MAX_SCORE + 1);
        assertThat(redis.hasKey("leaderboard-it:{" + game + ":season-1}:request:overflow")).isFalse();
    }
    @Test void concurrentAdjustmentsHaveNoLostUpdates() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String id = "event-" + i;
                requests.add(() -> adjust("alice", 1, id).statusCode());
            }
            for (var result : executor.invokeAll(requests)) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(json(call("GET", base + "/players/alice", null), 200).path("score").asLong()).isEqualTo(40);
    }
    @Test void concurrentRetriesApplyOnlyOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) requests.add(() -> adjust("alice", 5, "same-event").statusCode());
            for (var result : executor.invokeAll(requests)) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(json(call("GET", base + "/players/alice", null), 200).path("score").asLong()).isEqualTo(5);
    }
    @Test void rejectsMalformedInputWithoutWriting() throws Exception {
        for (String body : List.of("{}", "{\"score\":null}", "{\"score\":1.5}", "{\"score\":9007199254740992}", "not-json")) {
            json(call("PUT", base + "/players/alice/score", body), 400);
        }
        for (String query : List.of("limit=0", "limit=101", "offset=-1", "offset=2147483648", "order=wrong")) {
            json(call("GET", base + "/entries?" + query, null), 400);
        }
        json(call("GET", base + "/players/alice/neighbors?radius=26", null), 400);
        json(call("GET", base.replace(game, "bad.id") + "/entries", null), 400);
        json(call("POST", base + "/players/alice/score-adjustments", "{\"delta\":1}"), 400);
        json(call("POST", base + "/players/alice/score-adjustments", "{\"delta\":1,\"requestId\":\"bad:id\"}"), 400);
        assertThat(json(call("GET", base + "/stats", null), 200).path("participantCount").asLong()).isZero();
    }
    @Test void readinessIncludesRedis() throws Exception {
        assertThat(json(call("GET", "/actuator/health/readiness", null), 200).path("status").asText()).isEqualTo("UP");
        assertThat(json(call("GET", "/actuator/health/liveness", null), 200).path("status").asText()).isEqualTo("UP");
    }
}
