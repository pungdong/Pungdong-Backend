# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`pungdong` (풍덩) is the legacy Spring Boot backend for a freediving instructor ↔ student lecture/reservation matching service. Spring application name is `msa-legacy-service` — this service registers with a Eureka discovery server and is one piece of a larger MSA, not a standalone monolith.

Stack: Spring Boot 2.3.10, Java 11, Gradle, JPA + QueryDSL, MySQL (prod) / H2 (test), Spring Security + JWT, Redis, Kafka, Spring Data Elasticsearch, Spring HATEOAS, Spring Cloud Netflix Eureka client, Spring Cloud AWS (S3), Spring REST Docs (Asciidoctor).

## Commands

Build, test, generate docs (REST Docs → `build/generated-snippets` → asciidoctor → `static/docs` inside the boot jar):
```
./gradlew build
```

Tests only (JUnit 5 platform):
```
./gradlew test
```

Single test class / method:
```
./gradlew test --tests com.diving.pungdong.controller.account.AccountControllerTest
./gradlew test --tests com.diving.pungdong.controller.account.AccountControllerTest.<methodName>
```

Regenerate QueryDSL Q-classes (output: `build/generated/querydsl`, added to `main` source set):
```
./gradlew compileQuerydsl
```

Run locally:
```
./gradlew bootRun
```

Note: `bootJar` depends on `asciidoctor` which depends on `test`, so `./gradlew build` will fail the artifact step if any test fails. Use `./gradlew bootJar -x test -x asciidoctor` only when intentionally skipping docs.

## Runtime configuration

`PungdongApplication` explicitly sets `spring.config.location` to load **multiple YAML files** beyond the default `application.yml`:

- Classpath: `application.yml`, `database.yml`, `kafka.yml`, `redis.yml`, `aws.yml`
- Filesystem (production EC2 only): `/home/ubuntu/config/project/pungdong/{database,kafka,redis,aws}.yml`

Only `application.yml` is checked into `src/main/resources` — `database.yml`, `kafka.yml`, `redis.yml`, `aws.yml` are intentionally absent and supplied at deploy time. If you need to run locally end-to-end you must create these classpath files yourself; otherwise prefer the test profile.

`application.yml` references a Eureka server and an external authorization server by hard-coded IP. This service is not designed to start in isolation — expect Eureka registration attempts at boot.

## Test setup

- Active profile: `test` (`@ActiveProfiles("test")`) — loads `src/test/resources/application-test.yml` which switches the datasource to in-memory H2 with `MySQL5InnoDBDialect` overridden to H2.
- Redis: tests use an **embedded Redis server** (`it.ozimov:embedded-redis`) started by `EmbeddedRedisConfig` on the port from `spring.redis.port` (6379 by default — kill any local Redis on that port first, or change the port in `application-test.yml`).
- Controller tests follow the pattern `@SpringBootTest + @AutoConfigureMockMvc + @AutoConfigureRestDocs + @Import(RestDocsConfiguration.class)` with services replaced via `@MockBean`. They are responsible for emitting REST Docs snippets — when adding a new controller test, include `document(...)` calls so the generated documentation in `src/docs/asciidoc/api.adoc` stays complete.

## Code layout

Standard Spring layered architecture under `com.diving.pungdong`:

- `controller/` — REST endpoints, organized by feature (`account/`, `lecture/`, `schedule/`, `reservation/`, `review/`, `equipment/`, `location/`, `lectureImage/`, `profilePhoto/`, `sign/`).
- `service/` — business logic, mirroring controller features. Sub-packages exist for cross-cutting concerns: `service/kafka/` (producers/consumers + their DTOs), `service/elasticSearch/`, `service/image/`.
- `repo/` — Spring Data JPA repositories plus QueryDSL custom impls (`*RepoCustom` / `*RepoImpl` pattern). `repo/elasticSearch/` holds `ElasticsearchRepository` interfaces.
- `domain/` — JPA entities grouped by aggregate (`account/`, `lecture/`, `schedule/`, `reservation/`, `payment/`, `review/`, `equipment/`, `location/`). `domain/lecture/elasticSearch/` holds the `@Document` projections indexed in ES.
- `dto/` — request/response DTOs. **Convention**: `dto/<feature>/<operation>/` (e.g. `dto/lecture/create/`, `dto/account/signUp/`). Add new DTOs to the matching operation folder; create a new one if needed.
- `config/` — Spring `@Configuration` beans (Redis, Elasticsearch, email, HTTP client, i18n message source). `config/security/` holds `SecurityConfiguration`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `CurrentUser` (custom `@AuthenticationPrincipal` annotation), `UserAccount` (UserDetails wrapper).
- `advice/` — `@RestControllerAdvice` exception handling. Custom exceptions live in `advice/exception/`; user-facing messages are looked up via `MessageSource` against `src/main/resources/i18n/exception*.yml` (configured via `yaml-resource-bundle`).
- `model/` — `CommonResult` / `SingleResult<T>` / `ListResult<T>` / `SuccessResult` envelope types returned to clients. Build them through `ResponseService`.

## Security model

JWT-based, stateless (`SessionCreationPolicy.STATELESS`). `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`. URL → role mapping is centralized in `SecurityConfiguration.configure(HttpSecurity)` — when adding a new endpoint, update the matchers there. Roles in use: `ADMIN`, `INSTRUCTOR`, plus authenticated default. Several public endpoints (lecture browsing, sign-up/login, email code, password reset, exception lookup) are explicitly `permitAll`. Inject the current user via `@CurrentUser Account` rather than reading from `SecurityContextHolder` directly.

## Docs

REST Docs source: `src/docs/asciidoc/api.adoc` (Korean). The `bootJar` task copies the rendered HTML into `static/docs/` so it is served from the running app at `/docs/**` (whitelisted in `SecurityConfiguration.configure(WebSecurity)`). Snippets used by `api.adoc` come from controller tests — a new endpoint without a `document(...)` call in its test will leave the doc with broken includes.

## Deployment

GitHub Actions (`.github/workflows/deploy.yml`) builds on push to `master`, zips the workspace, uploads to S3 (`s3://pungdong-legacy/action_codedeploy/`), and triggers AWS CodeDeploy (application `pungdong`, group `dev`). On the EC2 host, `scripts/deploy.sh` (run via `appspec.yml` `AfterInstall` hook) kills the previous `pungdong-legacy` Java process and `nohup`s the new jar. Do not push to master casually — it deploys.
