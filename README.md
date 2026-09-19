# Leaderboard Service

A generic game leaderboard REST API built with Java 21, Spring Boot 4.1.1, Maven, and Redis Sorted Sets. Each game can have multiple independent leaderboards, such as all-time, weekly, or seasonal boards.

## Architecture

```text
  Game backend / Postman / HTTP client
                  |
                  | HTTP + JSON :8082
                  v
  +-------------------------------------------------------+
  | leaderboard-service (Java process on your computer)   |
  |                                                       |
  |  LeaderboardController                                |
  |       | validates IDs, scores, and query parameters    |
  |       v                                               |
  |  LeaderboardService                                   |
  |       | applies API semantics and missing-player rules|
  |       v                                               |
  |  LeaderboardRepository                                |
  |       | StringRedisTemplate + bounded Lua scripts      |
  |                                                       |
  |  Exception advice -> HTTP 400 / 404 / 409 / 503         |
  |  Actuator -> liveness + Redis-aware readiness          |
  +-------------------------|-----------------------------+
                            |
                            | Redis TCP localhost:6379
                            v
  +-------------------------------------------------------+
  | Docker Desktop: independently managed Redis container |
  |                                                       |
  | Sorted Set: leaderboard:{gameId:boardId}:scores         |
  |   member = playerId       score = integer points       |
  |                                                       |
  | Hash: leaderboard:{gameId:boardId}:request:requestId    |
  |   original player + delta + result, expires after 24h  |
  |                                                       |
  | /data -> Docker volume (AOF in recommended setup)      |
  +-------------------------------------------------------+
```

Redis is the primary leaderboard store. The application contains no Redis container definition and does not start or stop infrastructure. Application YAML only configures the connection. The project uses the controller/service/repository conventions of Image Storage Service and the independently managed infrastructure pattern of Kafka Integration Service.

## Requirements

- Java 21; the included Maven wrapper downloads Maven automatically.
- Docker Desktop running Linux containers.
- Free application port 8082 and an independently managed Redis 7.4+ instance on port 6379. Existing Redis 7 installations also support the commands used here.

## Run locally

### 1. Start Redis independently

In the original IdeaProjects workspace, reuse the existing infrastructure file (run from `leaderboard-service`):

```powershell
docker compose -f ..\docker\docker-compose.redis.yml up -d
docker exec redis-dev redis-cli ping
```

For a standalone clone without that sibling folder, create an independent Redis container once:

```powershell
docker run -d --name redis-dev -p 127.0.0.1:6379:6379 -v redis_dev_data:/data redis:7.4.2-alpine redis-server --appendonly yes
docker exec redis-dev redis-cli ping
```

Choose one setup. If `redis-dev` already exists, use `docker start redis-dev`; do not create a second container on the same port. Expected response: `PONG`. The standalone Docker command is an infrastructure setup step, not application startup. An existing container may have different persistence settings; inspect them with `docker exec redis-dev redis-cli CONFIG GET appendonly`. The provided setup examples enable AOF, but the application does not change Redis configuration.

### 2. Start the API

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```bash
./mvnw spring-boot:run
```

Alternatively, after building with `verify`:

```powershell
java -jar target/leaderboard-service-0.0.1-SNAPSHOT.jar
```

The API listens at `http://127.0.0.1:8082`. Confirm readiness:

```powershell
Invoke-RestMethod http://127.0.0.1:8082/actuator/health/readiness
```

Stop the Java process with Ctrl+C. Redis remains running. Stop Redis separately with `docker stop redis-dev`, or use the same infrastructure Compose file with `down`. A persistent volume survives ordinary container restarts/removal; deleting its volume deletes its data. AOF with the default every-second fsync is not a zero-data-loss guarantee.

## Configuration

| Environment variable | Default | Meaning |
|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | Local-only API binding |
| `SERVER_PORT` | `8082` | API port |
| `REDIS_HOST` | `localhost` | Independently hosted Redis |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | empty | Optional Redis password |
| `REDIS_DATABASE` | `0` | Logical database |
| `LEADERBOARD_KEY_PREFIX` | `leaderboard` | Namespace, 1-64 letters/digits/underscores/hyphens |
| `IDEMPOTENCY_TTL_SECONDS` | `86400` | Retry receipt lifetime, 1-604800 seconds |

Redis connect/command timeouts are two seconds. Set variables in the launching shell; the application does not automatically load `.env` files. If you later containerize the Java process with Docker Desktop, configure the host appropriately (for a host-published Redis port, `host.docker.internal`), and change the API bind address as needed.

## API

Base path: `/api/v1/games/{gameId}/leaderboards/{leaderboardId}`.

IDs, including `requestId`, contain 1-64 ASCII letters, digits, underscores, or hyphens. Boards are created on their first score write. An absent board has zero participants. Players are opaque IDs; profile data belongs to the game backend.

| Method | Path suffix | Request / result |
|---|---|---|
| PUT | `/players/{playerId}/score` | `{"score":100}` -> player ID and stored score |
| POST | `/players/{playerId}/score-adjustments` | `{"delta":25,"requestId":"match-42"}` -> score and `replayed` |
| GET | `/entries?offset=0&limit=10&order=desc` | Ranked page; limit 1-100, offset 0-2147483647 |
| GET | `/players/{playerId}?order=desc` | Player ID, score, and rank |
| GET | `/players/{playerId}/neighbors?radius=3&order=desc` | Target and up to `radius` entries on either side; radius 0-25 |
| GET | `/stats` | `{"participantCount":3}` |
| DELETE | `/players/{playerId}` | 204, including when the player is already absent |

All successful reads and writes return 200 except DELETE (204). Missing player reads return 404. Bad input returns 400, conflicting request IDs return 409, and Redis access failures return 503 using `application/problem+json`.

Example:

```powershell
$base = 'http://127.0.0.1:8082/api/v1/games/racing/leaderboards/season-2026'
Invoke-RestMethod -Method Put -Uri "$base/players/alice/score" -ContentType 'application/json' -Body '{"score":100}'
Invoke-RestMethod -Method Post -Uri "$base/players/alice/score-adjustments" -ContentType 'application/json' -Body '{"delta":25,"requestId":"match-42"}'
Invoke-RestMethod "$base/entries?limit=10"
```

Example ranked page:

```json
{
  "offset": 0,
  "limit": 10,
  "order": "desc",
  "entries": [{"playerId": "alice", "score": 125, "rank": 1}]
}
```

## Ranking, concurrency, and retries

- Higher scores rank first (`desc`); use `asc` consistently for lower-is-better games. Ranks are one-based ordinal positions, not shared competition ranks.
- Equal scores use Redis member ordering: reverse lexicographic player ID order for `desc`, lexicographic for `asc`. No timestamp tie breaker is implied.
- Scores/deltas must be integers between -9007199254740991 and 9007199254740991. Fractional scores and adjustments that exceed the result bounds are rejected. These bounds stay within exact double-precision integer representation.
- PUT is an absolute assignment with last-write-wins semantics. It does not deduplicate stale writes or order game events. Prefer score adjustments for additive events.
- POST atomically checks a retry receipt, checks the resulting score bounds, increments the sorted set, and stores the original result. An absent player starts at zero.
- A request ID is scoped to a game and board. Repeating it with the same player and delta returns the original score and `replayed: true`, even if later updates changed the current score. A different player/delta returns 409. Use GET for the latest score.
- Receipts expire after 24 hours by default, measured from the first accepted request; retries do not extend retention. After expiry or receipt loss, the same ID can apply again. This is bounded deduplication, not permanent exactly-once processing.
- Deleting a player retains unexpired receipts so old requests cannot immediately resurrect them. Use a fresh request ID for an intentional new adjustment.
- Read scripts obtain player score/rank and neighbor/page entries atomically. Different page requests see a live leaderboard and can shift under concurrent writes.
- Keys for a board use the same Redis hash tag so its adjustment script is compatible with Redis Cluster key-slot rules. This application is configured and tested against standalone Redis, not a cluster.
- Redis Lua execution prevents interleaving; scripts do not provide rollback after unexpected runtime/server failures. Keep the namespace owned by this service and use a suitable Redis memory/persistence policy. Evicting primary data or receipts changes these guarantees.
- Start a new season using a new leaderboard ID. Scores do not expire automatically; retention, scheduled cleanup, event ingestion, authentication, and anti-cheat logic are outside this initial service.

## How concurrent requests are handled

Multiple HTTP requests can execute concurrently on Spring's request threads. All application instances use the same Redis board keys; there is no JVM `synchronized` lock or in-memory score cache. Redis serializes each command/script that touches the data. The Lua adjustment script keeps these steps together:

```text
Request A: alice +5, requestId=match-42 ---+
                                        +---> Redis execution queue
Request B: alice +5, requestId=match-42 ---+              |
                                                       v
                          +-----------------------------------------+
                          | A: receipt absent                       |
                          |    validate resulting score             |
                          |    ZINCRBY +5                           |
                          |    store receipt with original result   |
                          +-----------------------------------------+
                                                       |
                                                       v
                          +-----------------------------------------+
                          | B: matching receipt exists              |
                          |    return saved score, replayed=true    |
                          |    no second increment                  |
                          +-----------------------------------------+

Outcome from a starting score of 0: score = 5, not 10.
Whichever identical request reaches Redis first performs the update.
```

Independent event IDs each increment the current Redis value. This avoids the lost-update problem of reading a score into Java, adding points, and writing it back while another request does the same thing.

The HTTP integration tests exercise concurrent requests using an eight-thread client pool:

| Tested flow | Expected and verified outcome |
|---|---|
| 40 unique request IDs, each adding 1 to the same player | All 40 return 200; final score is exactly 40 |
| 20 requests with one shared ID, each adding 5 | All 20 return 200; final score is exactly 5 |
| Retry an accepted +50 after an absolute overwrite to 500 | Retry returns the original 50; current score remains 500 |
| Reuse an ID with a different delta or player | 409 conflict; no extra adjustment |
| Increment past the maximum or minimum score | 400; score and retry receipt are not changed |

These are correctness checks, not throughput benchmarks. They cover simultaneous clients against one application process and Redis instance; a multi-instance/load test has not been performed.

Atomic read scripts keep each response's rank and score consistent. Scripts are bounded to at most 100 page entries or 51 neighbor entries so one request cannot scan the entire leaderboard and block other clients. Different HTTP responses can still represent different moments.

Ordering is determined by Redis execution order, not when a match occurred. Concurrent absolute PUT requests are last-write-wins. A PUT and a POST can also produce different final scores depending on their execution order. Event sequencing, permanent deduplication, and crash-proof processing would require a broader event-storage contract. Redis persistence and receipt retention limits described above still apply.

## Postman collection

Import [`postman/leaderboard-service.postman_collection.json`](postman/leaderboard-service.postman_collection.json), a Postman Collection v2.1 file.

The `baseUrl` variable defaults to `http://127.0.0.1:8082`. Run the collection in order: the first request generates a unique game ID and adjustment request ID for that run. Requests seed three players, verify ranking, pagination, ties, adjustments/replays/conflicts, ascending ranks, neighbors, isolation, validation, and deletion. Assertions check response bodies as well as HTTP status. The run removes its player entries; retry receipts expire automatically.

Command-line collection runner (requires Node.js/npm):

```powershell
npx --yes newman@6.2.1 run postman/leaderboard-service.postman_collection.json --reporters cli
```

## Tests and CI

```powershell
.\mvnw.cmd -B -ntp clean verify
```

`test` runs unit tests; `verify` also packages the service and runs HTTP integration tests against a real, isolated Redis container on a random port. Docker Desktop must be running. Tests use unique game IDs and never flush shared development Redis. A separate unavailable-backend test checks 503, readiness, and liveness behavior. GitHub Actions runs the same verification command.

Verified locally on 2026-09-19 before the initial commit:

| Verification | Result |
|---|---|
| Maven `clean verify` | 3 unit tests and 10 integration tests passed; none skipped |
| Packaged JAR running on port 8082 against Docker Desktop Redis | Readiness UP |
| Postman collection executed with Newman 6.2.1 against that running JAR | 29 requests, 61 assertions, zero failures |

The live check reused the existing `redis-dev` container, whose AOF setting was disabled. Its infrastructure configuration was not changed. This check verifies API behavior, not durability across a Redis crash.

The API is intended for trusted game backends and local development. It binds to loopback by default and has no authentication. Add authentication/authorization before exposing score-changing operations to untrusted clients.

## Project structure

```text
leaderboard-service/
|-- .github/workflows/ci.yml
|-- .mvn/wrapper/                 Maven wrapper configuration
|-- postman/                     Importable collection and flow assertions
|-- src/main/java/org/example/leaderboardservice/
|   |-- config/
|   |-- controller/
|   |-- dto/
|   |-- exception/
|   |-- repository/
|   `-- service/
|-- src/main/resources/
|   |-- application.yml          Connection settings only
|   `-- redis/                   Atomic adjustment and read scripts
|-- src/test/java/               Unit and real HTTP/Redis integration tests
|-- mvnw / mvnw.cmd
`-- pom.xml
```

Further reading: [Redis Sorted Sets](https://redis.io/docs/latest/develop/data-types/sorted-sets/), [Redis scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/), and [Spring Boot JSON configuration](https://docs.spring.io/spring-boot/reference/features/json.html).
