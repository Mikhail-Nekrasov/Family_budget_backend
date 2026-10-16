# Performance tests

Manual k6 tests for the Family Budget REST API. Run commands from the repository root.

## Requirements

- Docker and the project's PostgreSQL service.
- Java 17 or later compatible with the project, and the Maven wrapper.
- k6 (`brew install k6` on macOS).
- A separate local database named `family_budget_load` with the current Flyway migrations applied.

Use synthetic data only. These scripts create expenses and change account balances. They are not part of the normal CI/CD workflow.

## Local target restriction

Both JavaScript scripts must reject targets other than `http://localhost:8080`:

```javascript
if (baseUrl !== 'http://localhost:8080') {
    throw new Error('Load testing is allowed only at http://localhost:8080');
}
```

Keep the existing `baseUrl` declaration and place this check immediately after it. Remove any previous allowlist containing the production URL. Apply this to both `read-api.js` and `read-write-api.js`.

This restriction protects the remote application. Also check that the local application is connected to the separate load-test database, not the normal development database.

## Start the local application

Start PostgreSQL:

```bash
docker compose up -d postgres
```

Run the application against the existing load-test database:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/family_budget_load \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The URL above assumes the project's local PostgreSQL port is 5433. Check local credentials and port in the development configuration. Start only one application on port 8080.

## Prepare synthetic data

If `family_budget_load` already contains `perf_user_1` through `perf_user_100`, skip this section. The seed scripts are not intended to be rerun on an already seeded database.

For a separately prepared database with current migrations and without performance users, seed the first 20 users:

```bash
docker compose exec -T postgres \
  psql -U family_budget -d family_budget_load -v ON_ERROR_STOP=1 \
  < performance/seed.sql
```

Then add users 21 through 100:

```bash
docker compose exec -T postgres \
  psql -U family_budget -d family_budget_load -v ON_ERROR_STOP=1 \
  < performance/seed-extra-users.sql
```

The dataset contains 100 performance users, one account and two categories per performance user, and 18,250 historical entries per performance user (1,825,000 in total before load tests). The shared password `local-load-test-password` is for synthetic accounts only. Never reuse it for real users.

To prepare a fresh database, create a separate empty database and let the application apply Flyway migrations before seeding. Do not reset or seed the normal development or production database.

## Run tests

Initial read-only smoke test:

```bash
BASE_URL=http://localhost:8080 \
TEST_USERNAME_PREFIX=perf_user_ \
TEST_PASSWORD=local-load-test-password \
k6 run performance/read-api.js
```

Initial read/write smoke test:

```bash
BASE_URL=http://localhost:8080 \
TEST_USERNAME_PREFIX=perf_user_ \
TEST_PASSWORD=local-load-test-password \
k6 run performance/read-write-api.js
```

Increase the load in separate runs:

```bash
BASE_URL=http://localhost:8080 \
TEST_USERNAME_PREFIX=perf_user_ \
TEST_PASSWORD=local-load-test-password \
k6 run --vus 20 --duration 3m performance/read-write-api.js
```

Repeat with `--vus 50 --duration 3m`, then `--vus 100 --duration 5m` after successful smaller runs. At least one seeded user is needed per VU. Avoid overlapping runs or manually changing the performance accounts during a test.

## Scenarios and thresholds

- `read-api.js`: login, accounts, categories, paginated entries and summary.
- `read-write-api.js`: login, reads, expense creation, and verification that the account balance decreases by 1.25.
- Each VU uses its own `perf_user_<VU number>` account.
- Explicit pauses model users taking time between actions.
- All checks must pass; HTTP failure rate must be below 1%; read and write p95 must be below 1,000 ms.
- p95 is the response time at or below which 95% of measured requests complete.

Each completed read/write iteration leaves a new expense in the database. Repeated runs increase the row count and change balances.

## Recorded results — 16 October 2026

### Local read/write tests

| VUs | Duration | HTTP requests | Requests/s | Read p95 | Write p95 | Failed requests |
| --- | --- | --- | --- | --- | --- | --- |
| 20 | 3 min | 4,600 | 25.01 | 34.96 ms | 31.05 ms | 0 |
| 50 | 3 min | 11,500 | 62.68 | 27.14 ms | 37.52 ms | 0 |
| 100 | 5 min | 37,400 | 124.02 | 38.41 ms | 36.17 ms | 0 |

### Heroku read/write tests

Environment: one Basic dyno (512 MB), PostgreSQL Essential-0, PostgreSQL 18.4. JVM: `-Xms64m -Xmx256m -XX:+UseSerialGC -XX:ActiveProcessorCount=2`.

The synthetic database contained 100 performance users and 1,835,552 entries before remote tests; database size after restore was 523 MB. Each user's initial history was approximately five years at ten entries per day. Additional rows came from previous local runs. The dataset uses short synthetic descriptions and does not include receipt images.

| VUs | Duration | HTTP requests | Requests/s | Read p95 | Write p95 | Failed requests | Highest sampled dyno memory |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 min | 75 | 1.20 | 99.16 ms | 128.57 ms | 0 | 284.77 MB* |
| 20 | 3 min | 4,300 | 23.40 | 79.69 ms | 82.23 ms | 0 | 324.45 MB |
| 50 | 3 min | 10,585 | 57.43 | 85.90 ms | 86.83 ms | 0 | 332.23 MB |
| 100 | 5 min | 35,005 | 115.09 | 79.79 ms | 81.95 ms | 0 | 369.04 MB |

*For the 1-VU run, only two memory samples were supplied, so this is not a confirmed peak for the complete run. Other memory figures are maxima in the supplied logs, not continuous measurements.

All k6 checks passed, including balance checks. Supplied remote logs contained no detected server errors or swap usage. Remote timings include the network round trip from the load generator in Finland.

The remote tests were a controlled, temporary use of the existing deployment with synthetic data. The original database was restored afterwards and the application was checked. These instructions deliberately do not provide commands to repeat tests against production.

## Interpretation and limitations

The tested deployment sustained 100 active VUs in this scenario, about 115 HTTP requests/s, without failed requests. This is not a measurement of maximum capacity or total registered-user capacity.

The tests do not establish long-term memory stability, same-account concurrent write behaviour, shared-group workload performance, receipt upload performance, or performance of every API endpoint. Server throughput and database storage capacity are separate limits.

Do not commit database dumps, production credentials, authentication tokens or raw infrastructure logs. Keep reusable scripts and this summary in Git.
