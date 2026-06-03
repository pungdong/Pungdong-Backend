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

Run locally (one-time setup of Docker dependencies + yml files — see Runtime configuration):
```
docker compose up -d
cp src/main/resources/database.yml.example src/main/resources/database.yml
cp src/main/resources/redis.yml.example    src/main/resources/redis.yml
cp src/main/resources/aws.yml.example      src/main/resources/aws.yml
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew bootRun
```

`bootRun` requires direnv-loaded env vars (`JWT_SECRET`, `ADMIN_MAIL_ID`, `ADMIN_MAIL_PASSWORD`, `ELASTICSEARCH_URI`). See `.env.example`. If unset, Spring fail-fasts on placeholder resolution at boot.

Note: `bootJar` depends on `asciidoctor` which depends on `test`, so `./gradlew build` will fail the artifact step if any test fails. Use `./gradlew bootJar -x test -x asciidoctor` only when intentionally skipping docs.

## Runtime configuration

`PungdongApplication` explicitly sets `spring.config.location` to load **multiple YAML files** beyond the default `application.yml`:

- Classpath (required): `application.yml`, `database.yml`, `redis.yml`, `aws.yml`
- Filesystem (`optional:file:`, prod-only): `/home/ubuntu/config/project/pungdong/{database,redis,aws}.yml`

`application.yml` is committed. `database.yml` / `redis.yml` / `aws.yml` are gitignored — `.example` siblings are committed and copied via the `cp ... .example ...` commands above for local dev. The filesystem entries use `optional:file:` prefix so they can be missing locally without breaking boot; in production those paths exist and override the local copies.

Secrets (`spring.jwt.secret`, `AdminMail.id`, `AdminMail.password`, `elasticsearch.uri`) are externalized to env vars — see `.env.example` and `.env.local` (loaded by direnv). Fail-fast on missing vars at boot.

The local Docker stack (`docker compose up -d`) provides MySQL 8 (port 3306, db `pungdong`, user `pungdong/pungdongpw`), Redis 7 (port 6379), and Elasticsearch 7.17 (port 9200, security disabled). Spring connects via the values in the example yml files. Hibernate `hbm2ddl.auto: update` (in `application.yml`) auto-creates tables on first connect.

## Test setup

- Active profile: `test` (`@ActiveProfiles("test")`) — loads `src/test/resources/application-test.yml` which switches the datasource to in-memory H2 (the prod `MySQL5InnoDBDialect` is overridden to `H2Dialect` here).
- Redis: tests use an **embedded Redis server** (`com.github.codemonstur:embedded-redis:1.4.3`, the maintained arm64-compatible fork) started by `EmbeddedRedisConfig` on the port from `spring.redis.port` (6379 by default — kill any local Redis on that port first, or change the port in `application-test.yml`).
- Elasticsearch: **gated off in test profile**. `ElasticSearchConfig` is `@Profile("!test")`, three Boot ES auto-configs are excluded in `application-test.yml`, and `TestElasticSearchConfig` provides a Mockito-mocked `LectureEsRepo` so `LectureEsService` (which is real in many test contexts) can still autowire its dependency. **Do not "fix" this** — it's an intentional scaffold being torn down in Phase 3 when ES is removed entirely. The existing `LectureControllerTest > ElasticSearch에 강의 데이터 저장` test now passes against a mock and is essentially useless until Phase 3 — that's accepted.
- Controller tests follow the pattern `@SpringBootTest + @AutoConfigureMockMvc + @AutoConfigureRestDocs + @Import(RestDocsConfiguration.class) [+ EmbeddedRedisConfig.class]` with services replaced via `@MockBean`. They emit REST Docs snippets — when adding a new controller test, include `document(...)` calls so the generated documentation in `src/docs/asciidoc/api.adoc` stays complete.
- The `AuthUseCaseTest` (`src/test/java/com/diving/pungdong/usecase/`) is a Phase 1 safety net of use-case scenarios that run against the real Spring Security filter chain (no `@MockBean` for auth). When changing anything in `JwtTokenProvider`, `JwtAuthenticationFilter`, `SecurityConfiguration`, or auth flow controllers, **expect this test to catch regressions**. `JwtAuthenticationFilter` rejects blacklisted tokens (the Redis `"false"` marker written by `/sign/logout`), and `L1`/`L2` verify that logout invalidates the access and refresh tokens (401 JSON). (Two earlier carry-overs are now resolved — the logout no-op and the `RestDocsConfiguration` context-cache hack; see memory `phase_0_deferred_items`. Only the ES test scaffold remains, removed in Phase 3.)

## Code layout

**Mid-transition: layered → domain-based (package-by-feature).** Decided 2026-06-03 (memory `architecture-package-by-feature`). Spring is package-structure-agnostic — `@SpringBootApplication` at `com.diving.pungdong` scans everything beneath it regardless of layout. New/rewritten domains are organized **by feature** (one package holds that domain's controller + service + repo + entity + dto + a domain `CLAUDE.md`). Legacy domains still sit in the old layered packages until they're rewritten.

Settled feature packages (each auto-loads its own `CLAUDE.md` when you work in it):
- `account/` — auth/계정 (sign/email/account/profilePhoto controllers, AccountService etc., Account + Role/Gender/AuthProvider/DeviceType/FirebaseToken/ProfilePhoto/InstructorCertificate entities, `dto/`). Auth **infra** (`JwtTokenProvider`, `SecurityConfiguration`, `@CurrentUser`, `UserAccount`) lives in `global/security/`, not here — it's cross-cutting.
- `notification/` — domain events → outbox → FCM pipeline.
- `global/` — domain-agnostic shared: `global/config/`, `global/security/`, `global/advice/`, `global/model/` (CommonResult envelope), `global/ResponseService`.

Cross-domain coupling is expected in this monolith — e.g. `Account` is imported by ~35 files in other domains (lecture/reservation reference it as creator/applicant). That's fine; just keep the dependency direction one-way (account doesn't import lecture).

**Legacy layered packages** (`controller/ service/ repo/ domain/ dto/`) — still hold lecture/reservation/schedule/review/equipment/location/lectureImage. These get folded into feature packages as each is rewritten (lecture/reservation are pending a 기획 redesign). Old description of the layered layout below still applies to these:

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

Auth failures return **JSON `401`** directly via `CustomAuthenticationEntryPoint`; access denials return **JSON `403`** via `CustomAccessDeniedHandler` (both wired in `SecurityConfiguration.exceptionHandling`; bodies are the `CommonResult` `{success:false, code, msg}` envelope, code/msg from `MessageSource`). This replaced the old 302-redirect-to-`/exception/*` scheme (done in PR #19). `ExceptionController` now only exposes a **vestigial** `GET /exception/forbiddenToken` (throws `ForbiddenTokenException` → `ExceptionAdvice`) — it is not wired into the auth flow anymore and is a cleanup candidate.

### PII in requests — reads use GET, but PII parameters go in a POST body

**Repo rule (set 2026-06-03):** *"Reads use `GET`; but if a request parameter is PII, put it in a `POST` body."* PII in a query/path string leaks into server access logs, proxies, browser history, and `Referer` headers — HTTPS encrypts data *in transit* but not these *at-rest* logs. So a semantically-read endpoint whose input is PII becomes a `POST` to keep that PII out of URLs.

- **PII** = anything identifying a person: email, phone, real name, address, 주민등록번호, etc. A user-chosen **nickName (public display handle) is NOT PII** — it stays `GET`.
- Reference shape: `GET /sign/check/nickName?nickName=` (non-PII read) vs `POST /sign/check/email {email}` (PII read). The method asymmetry is **intentional and rule-driven**, not inconsistency. Both responses use `{ exists: boolean }`.
- **Claude must apply this proactively**: when adding or reviewing any endpoint whose request carries a PII value as a query or path parameter, *propose moving it to a POST body even if the user did not ask* — the user (Spring beginner) may not catch it. Flag the same whenever a PII value would otherwise end up in a URL or log line.

## API conventions

### HTTP status reflects request outcome, not the business answer

**Repo rule (set 2026-06-03):** an *expected* result — including a "negative" one the client branches on (nickname already taken, not eligible, empty list) — returns **`200` with a result field**. Reserve non-2xx for *genuine* failures:
- `400` malformed / invalid input (validation)
- `401` / `403` unauthenticated / unauthorized
- `404` a fetch-by-id whose resource truly does not exist
- `5xx` server faults

**Litmus test**: if the server correctly computed an answer and the client should render it as a normal UI state (not an error toast), it is `200` + a field. Do not `throw` to signal a business "no".

- Canonical example: `check/email` and `check/nickName` both return `200 {exists: boolean}` — duplicate is `exists:true`, **not** a `400`. (The opposite — throwing `BadRequestException` on duplicate — broke a FE client that treats every non-2xx as a network error; fixed 2026-06-03.)
- Split the two responsibilities: a *check/query* service method that powers an endpoint **returns** the answer (`checkNickNameExistence`, `checkEmailExistence`); a *guard* method that enforces an invariant during a write **throws** (`checkDuplicationOfNickName`, `checkDuplicationOfEmail` — used by sign-up / nickname-update).
- **Claude must apply this proactively**: when designing or reviewing an endpoint, if a normal expected outcome would surface as 4xx/5xx, propose the `200` + result-field shape instead — even if not asked.

(Request side — PII parameters belong in a POST body — is in the **Security model** section above.)

## Docs

REST Docs source: `src/docs/asciidoc/api.adoc` (Korean). The `bootJar` task copies the rendered HTML into `static/docs/` so it is served from the running app at `/docs/**` (whitelisted in `SecurityConfiguration.configure(WebSecurity)`). Snippets used by `api.adoc` come from controller tests — a new endpoint without a `document(...)` call in its test will leave the doc with broken includes.

## CI / Deployment

**CI (tests only)**: `.github/workflows/ci.yml` runs `./gradlew test` on every PR and on push to `master`. Uses JDK 17 / Temurin + gradle dependency caching. Test reports are uploaded as artifacts on failure. The workflow is **light** — no build / asciidoctor / bootJar, just tests (since asciidoctor is fixed but slower).

**No auto-deploy workflow yet.** The original `.github/workflows/deploy.yml` (S3 + CodeDeploy + EC2 `nohup java -jar`) was removed in PR #1 because the target infrastructure is offline and will be replaced in Phase 4 (AWS ECS Fargate per `phase_4_deployment_decisions.md` memory). `master` pushes only trigger CI tests, not deploy. Deploy workflow will be introduced when the deploy target exists.

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
- The `AuthUseCaseTest`, `NotificationOutboxFlowTest`, `SignUpUseCaseTest` under `src/test/java/com/diving/pungdong/usecase/` are the load-bearing safety nets.
- Each commit on a working branch: green tests required. Never `--no-verify`. Never disable a failing test to make a change pass — investigate the regression instead.
- For things tests cannot catch (real mobile-client compatibility, real FCM/SMTP delivery, real DB migrations against prod data), explicitly tell the user "this needs manual verification."

### Use-case test convention

When a feature ships, write **scenario-oriented** tests under `src/test/java/com/diving/pungdong/usecase/<Feature>UseCaseTest.java`, not coverage-driven unit tests. The user is a Spring beginner and reads tests as **executable spec** — they should be able to grok the feature by reading the `@DisplayName` lines top to bottom.

- **Real stack, not mocks**: `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`. Real H2, real Spring Security filter chain, real services. Only mock genuine external boundaries (`FcmGateway`, S3, third-party HTTP). Never `@MockBean` the service under test — verify final DB state via real `*JpaRepo` autowire.
- **Avoid `EmbeddedRedisConfig` unless the feature actually exercises Redis**: importing it forces a new Spring context that competes for port 6379 with `AuthUseCaseTest` / `SignControllerTest`. `RedisTemplate` autowires lazily, so as long as the test path doesn't call Redis ops you can omit it. (Root-cause fix scheduled for Phase 0.6/0.8 — random port or Testcontainers.)
- **`@DisplayName` is Korean prose, prefixed with a scenario code** (e.g. `S1`, `V2`, `D1`, `L1`). Group: `S*` = success/happy-path, `V*` = validation rejection, `D*` = duplicate/conflict, `L*` = login/logout interaction, `T*` = token-related, `R*` = role/authorization. Each line reads as a sentence ending with the observable outcome.
- **One scenario = one `@Test` method**: arrange (HTTP body), act (`mockMvc.perform(...)`), assert HTTP status + assert DB state via repo. Don't squeeze multiple branches into one test.
- **`@AfterEach` cleans up persisted rows** so tests are order-independent. Don't rely on `@Transactional` rollback for `@SpringBootTest` + MockMvc — the transaction boundary is inside the controller call, not the test method.
- **Comments at class level**: a short Javadoc explaining "read the `@DisplayName` lines top to bottom = spec." Patterns to copy: `AuthUseCaseTest` (HTTP-level + filter chain), `NotificationOutboxFlowTest` (event/repo-level + lifecycle), `SignUpUseCaseTest` (HTTP → DB end-to-end).

### Memory & context handoff

The user maintains permanent project context in `~/.claude/projects/<this-repo-path>/memory/`. Read those files at session start when relevant. Active files include:

- `MEMORY.md` — index
- `user_role.md` — solo side project, Spring beginner using day-job exposure as compounding learning
- `project_simplification_plan.md` — multi-phase roadmap with launch target ~2026-06-12; Phase 0+1 done; Phase 2 in progress
- `phase_0_deferred_items.md` — three intentional scaffolds that future-you must NOT "fix": (1) `AuthUseCaseTest.L1` captures the logout-no-op as current spec, (2) `AuthUseCaseTest`'s RestDocs import is a context-cache merging hack, (3) the ES `@Profile("!test")` + `TestElasticSearchConfig` mock is temporary until Phase 3

When `application.yml` placeholders, `@Profile("!test")` annotations, or `@MockBean` on services that we can't really mock look "wrong" — check memory first. They're often deliberate.

### TypeScript API contract

API 컨슈머는 모바일 + 웹 TypeScript 클라이언트들. 그들의 단일 출처는 [`docs/api-clients/types.ts`](docs/api-clients/types.ts).

**Per-PR 규칙 (도메인 문서와 동일 원칙)**: 컨트롤러 시그니처 / 응답 필드 / 도메인 enum / 공통 envelope 을 건드리는 PR 은 같은 커밋 / PR 안에서 `types.ts` 를 갱신한다.

세부 작성 규약 (enum 표기 / HAL 응답 패턴 / 섹션 주석 / FE Claude 핸드오프 프롬프트 등) 은 [`docs/api-clients/CLAUDE.md`](docs/api-clients/CLAUDE.md) — 그 디렉토리에서 작업할 때 자동 로드되는 좁은 컨텍스트.

자동화 (springdoc-openapi) 는 출시 이후 검토 — 현재는 솔로 dev + 출시 임박이라 수동 유지.

### Architectural changes update README + domain docs

Two layers of architecture documentation, both versioned in the repo:

1. **Root [README.md](README.md)** — single Mermaid diagram of the whole system. Update when the *system topology* changes (external dependencies, deployment shape, storage backends).
2. **[docs/architecture/<domain>.md](docs/architecture/)** — per-domain zoom-in. 7-section template. Update when *that domain's* components, flow, model, or permission matrix changes.

When a PR materially changes either layer, update the relevant doc(s) **in the same PR**. The docs are the orientation surface for human reviewers (the user is a Spring beginner — diagrams help them grok the change).

세부 작성 규약 (7-섹션 템플릿 / Mermaid fence 균형 검증 / 인덱스 충돌 resolve / 톤·스타일 / use-case 테스트 부재 처리) 은 [`docs/architecture/CLAUDE.md`](docs/architecture/CLAUDE.md) — 그 디렉토리에서 작업할 때 자동 로드되는 좁은 컨텍스트.

Examples that warrant a **root README** diagram update:
- Removing Kafka (Phase 2-C will do this)
- Adding/removing Elasticsearch (Phase 3 may do this)
- Switching from EC2/CodeDeploy to Docker/ECS (Phase 4)
- Adding a new external service (e.g. Stripe, payment processor)

Examples that warrant a **domain doc** update:
- Adding a new endpoint to a domain (changes the component map / permission matrix)
- Changing the request payload or response shape (sequence + ER diagram)
- Adding a new collaborating service inside the domain (component map)
- Shifting a behavior to a different lifecycle stage (e.g. moving CI verification out of sign-up)

Examples that do NOT warrant either update:
- Internal refactors (e.g. extracting a service class)
- Test-only infrastructure changes
- Config file restructuring

### Security/risk-sensitive operations require explicit user confirmation

- Never run `git push --force` to `master`
- Never modify global git config (`git config --global`)
- Never commit anything to `application.yml` that looks like a secret — externalize via `${ENV_VAR}` and document in `.env.example`
- Never bypass hooks (`--no-verify`)
- Confirm before: deleting branches, force-pushing, destructive db operations, anything that touches infrastructure
