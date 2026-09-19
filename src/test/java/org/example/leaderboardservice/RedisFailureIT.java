package org.example.leaderboardservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.data.redis.connect-timeout=200ms", "spring.data.redis.timeout=200ms"})
class RedisFailureIT {
    // Hold an unserved local TCP port, ensuring no real development Redis is affected.
    static final ServerSocket UNAVAILABLE;
    static {
        try { UNAVAILABLE = new ServerSocket(0, 10, InetAddress.getLoopbackAddress()); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", UNAVAILABLE::getLocalPort);
    }
    @org.junit.jupiter.api.AfterAll static void close() throws Exception { UNAVAILABLE.close(); }
    @Value("${local.server.port}") int port;
    @Test void unavailableRedisReturns503AndLivenessStaysUp() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/games/g/leaderboards/b/entries"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("temporarily unavailable");
            var ready = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(ready.statusCode()).isEqualTo(503);
            var live = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(live.statusCode()).isEqualTo(200);
        }
    }
}
