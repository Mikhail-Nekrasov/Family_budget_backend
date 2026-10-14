# Family Budget Backend

[![Backend CI](https://github.com/Mikhail-Nekrasov/Family_budget_backend/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Mikhail-Nekrasov/Family_budget_backend/actions/workflows/ci.yml)

Backend for a budgeting application built with Spring Boot and PostgreSQL. It provides a REST API for accounts, income, expenses, categories and transfers, together with a Thymeleaf web interface.

The project is being developed as the backend for a mobile application (which is in development now).

## Project status

Active development. The REST API and web interface run locally, with PostgreSQL migrations and automated tests. Account access currently follows an owner-based model; shared family access is not yet implemented. The mobile client is not included in this repository.

## Features

- Account creation, listing and deletion, with configurable initial balances.
- Income and expense creation, editing and deletion, with automatic balance updates.
- Personal and global categories, with filtering by entry type.
- Transfers between the authenticated user's accounts.
- Entry and transfer pagination.
- Income and expense summaries calculated in the database.
- Account expense totals and aggregate balances calculated in the database.
- Registration, login, logout and current-session checks.
- Session authentication, CSRF protection and ownership checks.
- Transactional financial operations and optimistic locking of account balances.
- Restrictions on deleting accounts and categories referenced by financial records.
- JSON responses for handled API errors.
- OpenAPI documentation and Swagger UI.

Amounts support up to two significant decimal places. Initial account balances may be negative; entry and transfer amounts must be positive.

## Technology stack

| Component | Technology |
| --- | --- |
| Language | Java 17 |
| Application framework | Spring Boot 4.1.1 |
| Persistence | Spring Data JPA and Hibernate |
| Database | PostgreSQL 17 |
| Schema migrations | Flyway |
| Authentication | Spring Security and BCrypt |
| Web interface | Thymeleaf and Bootstrap |
| JSON | Jackson 3 |
| API documentation | springdoc-openapi 3.1.1 and Swagger UI |
| Build | Maven Wrapper |
| Testing | JUnit, Mockito, MockMvc, H2 and Testcontainers |

## Local development

### Requirements

- JDK 17 or a compatible later version.
- Git.
- Docker with Docker Compose. On macOS, start Docker Desktop before running database or Testcontainers commands.

Maven is provided through the repository's wrapper.

### Start the application

```bash
git clone https://github.com/Mikhail-Nekrasov/Family_budget_backend.git
cd Family_budget_backend
docker compose up -d postgres
docker compose ps
```

Wait for PostgreSQL to become healthy, then start the application:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

The `dev` profile connects to PostgreSQL at `localhost:5433`. Flyway applies pending migrations, Hibernate validates the schema, and `DataInitializer` inserts demonstration data on the first run.

Open [http://localhost:8080](http://localhost:8080).

The development initializer creates a demonstration account with username `admin` and password `admin`. These are local demonstration credentials; the account is assigned `ROLE_USER`.

### Database configuration

Settings are defined in `compose.yaml` and `src/main/resources/application-dev.properties`:

| Setting | Local value |
| --- | --- |
| Database | `family_budget` |
| Username | `family_budget` |
| Password | `local_dev_password` |
| Host port | `5433` |
| Container port | `5432` |

These credentials are for local development. PostgreSQL is exposed only on the host's loopback interface. Data is stored in a Docker named volume and survives container restarts.

Stop the database without deleting its data:

```bash
docker compose down
```

Inspect the database tables:

```bash
docker compose exec postgres psql -U family_budget -d family_budget -c "\dt"
```

Migrations are stored in `src/main/resources/db/migration`. Add schema changes as new versioned migrations; do not modify migrations already applied to a database.

## REST API

### Documentation

After logging in through the web interface, open:

- [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)

Swagger UI uses the browser's existing session. For POST, PUT and DELETE requests, obtain a token from `GET /api/auth/csrf` and enter its `token` value in the `csrfToken` field of the **Authorize** dialog. The browser supplies the session cookie automatically.

- [Group access and permissions](docs/group-access.md)

### Endpoints

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/accounts` | List own accounts |
| POST | `/api/accounts` | Create an account |
| DELETE | `/api/accounts/{id}` | Delete an unused account |
| GET | `/api/categories` | List personal and global categories; optional `type` filter |
| POST | `/api/categories` | Create a personal category |
| DELETE | `/api/categories/{id}` | Delete an unused personal category |
| GET | `/api/entries` | List own entries; optional `type`, `page` and `size` parameters |
| POST | `/api/entries` | Create an entry |
| PUT | `/api/entries/{id}` | Update an entry and recalculate balances |
| DELETE | `/api/entries/{id}` | Delete an entry and reverse its balance effect |
| GET | `/api/entries/summary` | Return total income, total expense and their difference |
| GET | `/api/transfers` | List own transfers; optional `page` and `size` parameters |
| POST | `/api/transfers` | Create a transfer |
| GET | `/api/auth/csrf` | Obtain a CSRF token |
| POST | `/api/auth/register` | Register a user |
| POST | `/api/auth/login` | Log in |
| GET | `/api/auth/me` | Identify the current authenticated user |
| POST | `/api/auth/logout` | End the session |

Pages are zero-based. Entries and transfers default to `page=0` and `size=20`; page sizes must be between 1 and 100.

Entry summaries cover all of the user's entries. Initial account balances and transfers are not counted as income or expenses in these summaries.

### Authentication and CSRF

The API uses session authentication with a `JSESSIONID` cookie, rather than bearer tokens.

1. Call `GET /api/auth/csrf` and retain the session cookie.
2. Use the returned `headerName` and `token` for requests that change data, including registration and login.
3. Submit login credentials to `POST /api/auth/login` as `application/x-www-form-urlencoded` fields named `username` and `password`. Registration and financial operations use JSON request bodies.
4. After successful login, retain the updated session cookie and obtain a fresh CSRF token.
5. Include the session cookie and CSRF header in subsequent POST, PUT and DELETE requests.
6. Call `POST /api/auth/logout` to end the session.

Unauthenticated protected API requests return `401`; CSRF failures return `403`.

## Testing

With Docker running, execute the complete test suite:

```bash
./mvnw test
```

The suite includes unit tests, HTTP integration tests, rollback tests, optimistic locking checks and PostgreSQL schema-constraint tests.

Most application integration tests use H2 with the `test` profile. PostgreSQL tests use the `postgres-test` profile, apply Flyway migrations, and start separate temporary PostgreSQL containers through Testcontainers. They do not use the development database from Docker Compose.

Run the PostgreSQL tests only:

```bash
./mvnw '-Dtest=Postgres*Test' test
```

The first Testcontainers run may download additional Docker images. Starting the development database with Docker Compose is not required for these tests.
