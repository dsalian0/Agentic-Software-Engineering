# URL Shortener

A Spring Boot 3 / Java 21 service that creates short links, redirects them with 302, records click analytics and rejects abusive input. It is backed by PostgreSQL through Flyway, or by H2 under the `local` profile.

This code is also the cumulative output of the orchestrator's greenfield, brownfield and ambiguous scenarios; see [../docs/SCENARIOS.md](../docs/SCENARIOS.md).

## API

| Method | Path | Description | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/links` | Create `{targetUrl, customAlias?}` | 201 + `Location` | 400 invalid or unsafe URL, 409 alias taken, 429 rate limit, 503 code space |
| GET | `/api/v1/links/{code}` | Look up a link | 200 | 404 |
| GET | `/api/v1/links?page&size&sort` | List, newest first (size ≤ 100) | 200 `{content,page,size,totalElements,totalPages}` | |
| GET | `/r/{code}` | Redirect (`Cache-Control: private, no-store`) | 302 | 404 |
| GET | `/api/v1/links/{code}/stats?from&to` | Clicks per UTC day (inclusive, default last 30 days, max 366) | 200 | 400 bad range, 404 |

Errors are RFC 7807 problem details. OpenAPI is at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`, and health and metrics at `/actuator/health` and `/actuator/metrics`.

Example stats response:

```json
{"code":"demo-docs","from":"2026-10-18","to":"2026-11-16","totalClicks":2,
 "daily":[{"date":"2026-11-16","clicks":2,"uniqueVisitors":1}],
 "topReferrers":[{"referrer":"(direct)","clicks":1},{"referrer":"news.example.org","clicks":1}],
 "userAgentFamilies":{"DESKTOP":1,"MOBILE":1}}
```

## Design notes

- **Codes.** 7-character random Base62 codes from `SecureRandom` (about 3.5 × 10¹² combinations, not enumerable). A collision is retried up to 5 times. The database unique constraint is the final check; each insert runs in its own transaction, so a retry after a race starts clean.
- **302, not 301.** Browsers cache 301 redirects, which would hide clicks and freeze the target.
- **Analytics off the redirect path.** Each redirect publishes a `LinkResolvedEvent`. `ClickRecorder` only offers it to a bounded queue; a background writer inserts in batches. When the buffer is full, or the database fails twice, clicks are dropped and counted in `shortener.clicks.dropped`. Redirects never wait for or fail because of analytics. Pending clicks are flushed on graceful shutdown.
- **Privacy.** No raw IP is stored. Visitors are counted with `SHA-256(salt | UTC day | address)`, so unique visitors are per day and cannot be tracked across days.
- **Target safety.** Every `TargetUrlPolicy` bean runs in order:
  - only absolute `http`/`https` URLs with a host
  - no embedded credentials
  - no localhost, `.local` or `.internal` hosts
  - no loopback, private, link-local, unique-local, multicast or encoded IP literals
  - no links back to this service
  - no domains on `shortener.safety.blocked-domains`

  Hostnames are not resolved through DNS (a documented limitation).
- **Rate limit.** A per-client token bucket on `POST /api/v1/links` only. Exceeding it returns 429 with `Retry-After`. Memory is bounded: idle buckets are evicted.

## Configuration

| Property | Default |
|---|---|
| `shortener.base-url` (`SHORTENER_BASE_URL`) | `http://localhost:8080` |
| `shortener.code-length` / `max-generation-attempts` | `7` / `5` |
| `shortener.analytics.queue-capacity` / `batch-size` / `flush-interval-ms` | `10000` / `500` / `1000` |
| `shortener.analytics.visitor-salt` | random per process; set it in production |
| `shortener.safety.blocked-domains` | empty |
| `shortener.rate-limit.enabled` / `capacity` / `refill-per-minute` | `true` / `30` / `30` |
| `SPRING_DATASOURCE_URL` / `USERNAME` / `PASSWORD` | `jdbc:postgresql://localhost:5432/shortenerdb`, `postgres`, `postgres` (local development only) |

## Build and run

```bash
./mvnw -B clean verify
```

```bash
java -jar target/url-shortener-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

The first command runs the 73 tests; the second starts the service on in-memory H2 with no database server needed. On Windows use `mvnw.cmd`.
