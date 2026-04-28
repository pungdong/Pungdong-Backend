# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`pungdong` (풍덩) is the Spring Boot backend for a freediving instructor ↔ student lecture/reservation matching service. The Spring application name is still `msa-legacy-service` (the name predates the in-progress consolidation), but the service no longer registers with Eureka and is being merged toward a single self-contained Boot jar. One external dependency remains: the OAuth2 authorization server at `${authorization-server.host}` is still called from `AuthService.getAuthToken` for login token issuance — this is scheduled for absorption in Phase 1.

Stack: Spring Boot **2.7.18**, Java **17**, Gradle **7.6.4**, JPA + Spring Data Specifications, MySQL (prod) / H2 (test), Spring Security 5.7 + JWT, Redis, Kafka, Spring Data Elasticsearch, Spring HATEOAS, `io.awspring.cloud:spring-cloud-starter-aws:2.4.4` (community fork; AWS SDK v1 path used by `S3Uploader`), Spring REST Docs (Asciidoctor 2.4).

## Commands

The user has multiple JDKs installed; tests must run on JDK 17. Prefix each gradle invocation:

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test
```

Build, test, generate docs (REST Docs → `build/generated-snippets` → asciidoctor → `static/docs` inside the boot jar):
```
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build
```

Single test class / method:
```
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests com.diving.pungdong.controller.account.AccountControllerTest
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests com.diving.pungdong.controller.account.AccountControllerTest.<methodName>
```

Run locally (also requires the missing yml files — see Runtime configuration):
```
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew bootRun
```

Note: `bootJar` depends on `asciidoctor` which depends on `test`, so `./gradlew build` will fail the artifact step if any test fails. Use `./gradlew bootJar -x test -x asciidoctor` only when intentionally skipping docs.

## Runtime configuration

`PungdongApplication` explicitly sets `spring.config.location` to load **multiple YAML files** beyond the default `application.yml`:

- Classpath: `application.yml`, `database.yml`, `kafka.yml`, `redis.yml`, `aws.yml`
- Filesystem (production EC2 only): `/home/ubuntu/config/project/pungdong/{database,kafka,redis,aws}.yml`

Only `application.yml` is checked into `src/main/resources` — `database.yml`, `kafka.yml`, `redis.yml`, `aws.yml` are intentionally absent and were supplied at deploy time. The prod EC2 host is currently offline; running `bootRun` end-to-end requires creating these files locally. Otherwise prefer the test profile.

`application.yml` still references the external authorization server by hardcoded IP (`authorization-server.host`) and contains plaintext secrets (Gmail SMTP password, JWT secret, OAuth client secret). Externalization + rotation is scheduled for Phase 1.

## Test setup

- Active profile: `test` (`@ActiveProfiles("test")`) — loads `src/test/resources/application-test.yml` which switches the datasource to in-memory H2 (the prod `MySQL5InnoDBDialect` is overridden to `H2Dialect` here).
- Redis: tests use an **embedded Redis server** (`com.github.codemonstur:embedded-redis:1.4.3`, the maintained arm64-compatible fork) started by `EmbeddedRedisConfig` on the port from `spring.redis.port` (6379 by default — kill any local Redis on that port first, or change the port in `application-test.yml`).
- Elasticsearch: **gated off in test profile**. `ElasticSearchConfig` is `@Profile("!test")`, three Boot ES auto-configs are excluded in `application-test.yml`, and `TestElasticSearchConfig` provides a Mockito-mocked `LectureEsRepo` so `LectureEsService` (which is real in many test contexts) can still autowire its dependency. **Do not "fix" this** — it's an intentional scaffold being torn down in Phase 3 when ES is removed entirely. The existing `LectureControllerTest > ElasticSearch에 강의 데이터 저장` test now passes against a mock and is essentially useless until Phase 3 — that's accepted.
- Controller tests follow the pattern `@SpringBootTest + @AutoConfigureMockMvc + @AutoConfigureRestDocs + @Import(RestDocsConfiguration.class) [+ EmbeddedRedisConfig.class]` with services replaced via `@MockBean`. They emit REST Docs snippets — when adding a new controller test, include `document(...)` calls so the generated documentation in `src/docs/asciidoc/api.adoc` stays complete.
- The `AuthUseCaseTest` (`src/test/java/com/diving/pungdong/usecase/`) is a Phase 1 safety net of 10 use-case scenarios that run against the real Spring Security filter chain (no `@MockBean` for auth). When changing anything in `JwtTokenProvider`, `JwtAuthenticationFilter`, `SecurityConfiguration`, or auth flow controllers, **expect this test to catch regressions**. Two intentional quirks in this file:
  - `L1: 로그아웃 후에도 같은 access token으로 보호된 API 통과` captures the **current logout no-op** (the filter does not check the Redis blacklist that `/sign/logout` writes to). When Phase 1/2 wires up the blacklist check, this test must be **consciously updated** (assertion + `@DisplayName`), not silently fixed.
  - The class carries `@AutoConfigureRestDocs` + `RestDocsConfiguration` import only as a context-cache-merging hack to share the embedded-Redis instance with `SignControllerTest`. Do not delete those annotations until Phase 0.6/0.8 wrap-up addresses the root cause (random port or Testcontainers).

## Code layout

Standard Spring layered architecture under `com.diving.pungdong`:

- `controller/` — REST endpoints, organized by feature (`account/`, `lecture/`, `schedule/`, `reservation/`, `review/`, `equipment/`, `location/`, `lectureImage/`, `profilePhoto/`, `sign/`).
- `service/` — business logic, mirroring controller features. Sub-packages exist for cross-cutting concerns: `service/kafka/` (producers/consumers + their DTOs — scheduled for removal in Phase 2), `service/elasticSearch/` (scheduled for removal in Phase 3), `service/image/` (S3 upload via AWS SDK v1).
- `repo/` — Spring Data JPA repositories. Dynamic queries use **`JpaSpecificationExecutor` + a sibling `*Specifications` utility class** (e.g. `LectureSpecifications`); QueryDSL was removed in Phase 0.4. `repo/elasticSearch/` holds an `ElasticsearchRepository` interface that won't be instantiated under the `test` profile (see Test setup).
- `domain/` — JPA entities grouped by aggregate (`account/`, `lecture/`, `schedule/`, `reservation/`, `payment/`, `review/`, `equipment/`, `location/`). `domain/lecture/elasticSearch/` holds the `@Document` projections indexed in ES.
- `dto/` — request/response DTOs. **Convention**: `dto/<feature>/<operation>/` (e.g. `dto/lecture/create/`, `dto/account/signUp/`). Add new DTOs to the matching operation folder; create a new one if needed.
- `config/` — Spring `@Configuration` beans (Redis, Elasticsearch, email, HTTP client, i18n message source). `config/security/` holds `SecurityConfiguration` (still extends the **deprecated** `WebSecurityConfigurerAdapter` — migration to `SecurityFilterChain` bean is scheduled with the Phase 1 auth absorption), `JwtTokenProvider`, `JwtAuthenticationFilter`, `CurrentUser` (custom `@AuthenticationPrincipal` annotation), `UserAccount` (`UserDetails` wrapper).
- `advice/` — `@RestControllerAdvice` exception handling. Custom exceptions live in `advice/exception/`; user-facing messages are looked up via `MessageSource` against `src/main/resources/i18n/exception*.yml` (configured via `yaml-resource-bundle`).
- `model/` — `CommonResult` / `SingleResult<T>` / `ListResult<T>` / `SuccessResult` envelope types returned to clients. Build them through `ResponseService`.

## Security model

JWT-based, stateless (`SessionCreationPolicy.STATELESS`). `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`. URL → role mapping is centralized in `SecurityConfiguration.configure(HttpSecurity)` — when adding a new endpoint, update the matchers there. Roles: `ADMIN`, `INSTRUCTOR`, `STUDENT` (the default for new sign-ups). Several public endpoints (lecture browsing, sign-up/login, email code, password reset, exception lookup) are explicitly `permitAll`. Inject the current user via `@CurrentUser Account` rather than reading from `SecurityContextHolder` directly.

Auth failures redirect (302) to `/exception/entrypoint`; access denials redirect to `/exception/accessDenied`. These endpoints are mapped in `ExceptionController` and translate into JSON via `ExceptionAdvice`. This is unusual for a JSON API — a switch to direct 401/403 responses is on the table for the Phase 1 auth absorption.

## Docs

REST Docs source: `src/docs/asciidoc/api.adoc` (Korean). The `bootJar` task copies the rendered HTML into `static/docs/` so it is served from the running app at `/docs/**` (whitelisted in `SecurityConfiguration.configure(WebSecurity)`). Snippets used by `api.adoc` come from controller tests — a new endpoint without a `document(...)` call in its test will leave the doc with broken includes.

## Deployment

**There is no auto-deploy workflow currently.** The original `.github/workflows/deploy.yml` (S3 + CodeDeploy + EC2 `nohup java -jar`) was removed in PR #1 because the target infrastructure is offline and will be replaced in Phase 4 (Docker/ECS or systemd, TBD). `master` pushes do not trigger anything. A new workflow will be introduced when the deploy target exists.

`scripts/deploy.sh` and `appspec.yml` are leftover from the old deploy and are not currently invoked by anything; don't rely on them as a guide.

## Workflow & conventions

The user is a solo dev running this as a side project and chose a strict PR-based workflow on 2026-04-27. **These conventions hold across sessions** — any future Claude instance picking up work in this repo should follow them.

### Branching

- One branch per work unit. Never push directly to `master`.
- Branch from latest `master`. Sync with `git pull origin master --ff-only` first.
- Naming: `<prefix>/<short-name>` where prefix matches intent
  - `phase-N/<short>` for roadmap phases (e.g. `phase-2/notification-events-outbox`)
  - `refactor/<short>` for code refactors not tied to a phase
  - `chore/<short>` for ops/config (CI workflow toggles, dep bumps unrelated to a phase)
  - `docs/<short>` for documentation-only PRs
  - `fix/<short>` for bugfixes
- Korean is fine in branch names but ASCII-only avoids Git tooling quirks; default to short English slugs.

### Commits within a PR

- **Atomic**: one logical change per commit
- **Buildable**: each commit must leave the codebase compiling AND with a green test suite — `git bisect` works at commit granularity
- **Tellable**: commit subject in the existing log style — `Refactor:`, `Fix:`, `Test:`, `Docs:`, `Chore:`, `Feat:` (capitalized prefix, colon, then a sentence)
- Multi-commit PRs (the norm for anything non-trivial) are squash-merged into `master` via the GitHub UI — granular commit history is preserved on the branch and visible in the PR detail page, useful for postmortem `git bisect`

### PR descriptions

- **Korean**, structured with these sections:
  - **요약** (1-2 sentences: what changed + headline test result)
  - **왜** (motivation; why now; tradeoff considered if any)
  - **무엇이 바뀜 / 어떻게 동작하나** (the actual changes; tables when listing files; diagrams when flow shifted)
  - **테스트** (`./gradlew test` count, key safety-net tests, manual verification steps)
  - **본인이 PR 머지와 별개로 직접 처리할 것** (anything outside the diff: secret rotation, infra setup, mobile-side changes)
  - **Phase X 진행 상황** (when applicable; show what's done vs queued)
- Past examples to follow: PR #6 (small refactor), PR #9 (large architectural change), PR #11 (small follow-up). All in the repo's PR list.

### Verification strategy

- Each architectural change starts with **use-case integration tests** that exercise the real Spring Security filter chain with H2 — written BEFORE the code change. The user reviews test scenarios in plain Korean (not code) since they are Spring-beginner.
- Existing `@MockBean`-heavy controller tests are NOT trustworthy as regression catchers — they verify HTTP wiring + REST Docs only, not business logic.
- The `AuthUseCaseTest` (10 scenarios) and `NotificationOutboxFlowTest` (6 scenarios) under `src/test/java/com/diving/pungdong/usecase/` are the load-bearing safety nets.
- Each commit on a working branch: green tests required. Never `--no-verify`. Never disable a failing test to make a change pass — investigate the regression instead.
- For things tests cannot catch (real mobile-client compatibility, real FCM/SMTP delivery, real DB migrations against prod data), explicitly tell the user "this needs manual verification."

### Memory & context handoff

The user maintains permanent project context in `~/.claude/projects/<this-repo-path>/memory/`. Read those files at session start when relevant. Active files include:

- `MEMORY.md` — index
- `user_role.md` — solo side project, Spring beginner using day-job exposure as compounding learning
- `project_simplification_plan.md` — multi-phase roadmap with launch target ~2026-06-12; Phase 0+1 done; Phase 2 in progress
- `phase_0_deferred_items.md` — three intentional scaffolds that future-you must NOT "fix": (1) `AuthUseCaseTest.L1` captures the logout-no-op as current spec, (2) `AuthUseCaseTest`'s RestDocs import is a context-cache merging hack, (3) the ES `@Profile("!test")` + `TestElasticSearchConfig` mock is temporary until Phase 3

When `application.yml` placeholders, `@Profile("!test")` annotations, or `@MockBean` on services that we can't really mock look "wrong" — check memory first. They're often deliberate.

### Architectural changes update README

When a PR materially changes the architecture (removes/adds a major external dependency, changes the deployment topology, swaps a storage backend), update `README.md` — specifically the **Mermaid architecture diagram** — in the same PR. The diagram is the single source of truth for newcomer orientation.

Examples that warrant a diagram update:
- Removing Kafka (Phase 2-C will do this)
- Adding/removing Elasticsearch (Phase 3 may do this)
- Switching from EC2/CodeDeploy to Docker/ECS (Phase 4)
- Adding a new external service (e.g. Stripe, payment processor)

Examples that do NOT warrant an update:
- Internal refactors (e.g. extracting a service class)
- Test-only infrastructure changes
- Config file restructuring

### Security/risk-sensitive operations require explicit user confirmation

- Never run `git push --force` to `master`
- Never modify global git config (`git config --global`)
- Never commit anything to `application.yml` that looks like a secret — externalize via `${ENV_VAR}` and document in `.env.example`
- Never bypass hooks (`--no-verify`)
- Confirm before: deleting branches, force-pushing, destructive db operations, anything that touches infrastructure
