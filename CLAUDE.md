# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`pungdong` (풍덩) is the Spring Boot backend for a freediving instructor ↔ student lecture/reservation matching service. The Spring application name is still `msa-legacy-service` (the name predates the consolidation), but the service no longer registers with Eureka and is now a single self-contained Boot jar. Login token issuance is fully in-process (JWT minted by `JwtTokenProvider` in `account/`) — the former external OAuth2 authorization server dependency was absorbed in Phase 1. (Social OAuth — Kakao/Naver — is a separate future feature, deferred post-launch; see memory `project_simplification_plan`.)

Stack: Spring Boot **2.7.18**, Java **17**, Gradle **7.6.4**, JPA + Spring Data Specifications, MySQL (prod) / H2 (test), Spring Security 5.7 + JWT, Redis, Spring HATEOAS, `io.awspring.cloud:spring-cloud-starter-aws:2.4.4` (community fork; AWS SDK v1 path used by `S3Uploader`), Spring REST Docs (Asciidoctor JVM 3.3). Kafka was removed in Phase 2 (domain events + notification outbox replaced it); Elasticsearch was removed in Phase 3 (search is now MySQL `JpaSpecification` — title/instructor `LIKE`).

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

After the one-time setup above, the day-to-day shortcut is **`./scripts/dev.sh`** — it cleans any process on 8080 (so it doubles as a restart), runs `docker compose up -d`, loads `.env.local`, and starts `bootRun` on JDK 17. (`scripts/dev.sh` is the only thing that needs to be in the permission allowlist for hands-off restarts.)

`bootRun` requires direnv-loaded env vars (`JWT_SECRET`, `ADMIN_MAIL_ID`, `ADMIN_MAIL_PASSWORD`). See `.env.example`. If unset, Spring fail-fasts on placeholder resolution at boot.

Note: `bootJar` depends on `asciidoctor` which depends on `test`, so `./gradlew build` will fail the artifact step if any test fails. Use `./gradlew bootJar -x test -x asciidoctor` only when intentionally skipping docs.

## Runtime configuration

`PungdongApplication` explicitly sets `spring.config.location` to load **multiple YAML files** beyond the default `application.yml`:

- Classpath (required): `application.yml`, `database.yml`, `redis.yml`, `aws.yml`
- Filesystem (`optional:file:`, prod-only): `/home/ubuntu/config/project/pungdong/{database,redis,aws}.yml`

`application.yml` is committed. `database.yml` / `redis.yml` / `aws.yml` are gitignored — `.example` siblings are committed and copied via the `cp ... .example ...` commands above for local dev. The filesystem entries use `optional:file:` prefix so they can be missing locally without breaking boot; in production those paths exist and override the local copies.

Secrets (`spring.jwt.secret`, `AdminMail.id`, `AdminMail.password`) are externalized to env vars — see `.env.example` and `.env.local` (loaded by direnv). Fail-fast on missing vars at boot.

The local Docker stack (`docker compose up -d`) provides MySQL 8 (port 3306, db `pungdong`, user `pungdong/pungdongpw`) and Redis 7 (port 6379). Spring connects via the values in the example yml files.

### Schema = Flyway migrations (not hbm2ddl)

**`hbm2ddl.auto: validate`** — Hibernate **validates** the schema against entities at boot and **never mutates** it. The schema is owned by **Flyway** migrations in `src/main/resources/db/migration/V<N>__<name>.sql`, run in order once each (tracked in `flyway_schema_history`). Adopted 2026-06-28 (#111) after `hbm2ddl=update` left drift (it only adds, never drops/alters — a removed entity field's column lingered as `NOT NULL` and broke inserts).

- **Every schema change ships its own `V<N>__...sql` migration in the same PR.** Changing an entity's columns/tables without a matching migration → boot fails at `validate` (that's the point — drift surfaces immediately, not as a runtime 500).
- `V1__baseline.sql` = the schema as of adoption (full `CREATE TABLE`s, dumped). Existing DBs (local/staging/prod) are **baselined** at V1 (`baseline-on-migrate`, marked applied without running); fresh DBs run V1 to create everything. `V2__...` = drop of the legacy `enrollment` leftover columns (conditional via `information_schema` — no-op where already clean, converges drifted envs).
- **Tests stay on H2 `create-drop` + Flyway off** (`application-test.yml`) — H2 can't run MySQL migration SQL, so tests build the schema straight from entities. (So a forgotten migration won't fail tests — it fails at a real-DB `validate` boot. Manually boot `./scripts/dev.sh` after entity changes.)
- New migration: next version number, plain MySQL SQL.
- **Migrations MUST be idempotent** — `CREATE TABLE IF NOT EXISTS`, `DROP TABLE IF EXISTS`; for adds/drops MySQL lacks `IF [NOT] EXISTS` on (`ADD`/`DROP COLUMN`, foreign keys), use the `information_schema` + stored-procedure pattern (see `V2`). **Why (2026-06-28 prod incident, #121):** ECS restarts a failed task fast, so the same migration can run **concurrently / be retried**. A bare `CREATE TABLE` then hits `table already exists` (1050) → the migration is recorded as *failed* → every later boot is blocked. Idempotent SQL survives concurrent/retried runs. (`V1__baseline` was retrofitted to `IF NOT EXISTS` after this bit prod.)
- **Test a new migration against a fresh DB *through Flyway*, not just `mysql`** — running `mysql < V_.sql` proves the SQL parses, but NOT that Flyway executes it cleanly (concurrency, statement splitting). Local boot only *baselines* V1 (doesn't run it), and CI is H2+Flyway-off — so the first real Flyway execution can be **prod**. Boot the app against an empty docker MySQL (or see #123) before deploying. Full incident write-up: [docs/architecture/deployment.md](docs/architecture/deployment.md).

## Test setup

> **테스트 아키텍처(hermetic — 외부 무의존) 원칙은 [docs/architecture/testing.md](docs/architecture/testing.md).** 단위·통합 테스트는 외부 서비스(PG·juso·본인확인·Sanity·FCM·S3)를 실호출하지 않는다 — 경계마다 stub 프로파일 핀(A) 또는 `@MockBean`(B)으로 격리. 새 외부 서비스 붙일 때 그 문서의 체크리스트를 따른다. "로컬에서만 테스트가 깨지면" 셸 env 누출부터 의심.

- Active profile: `test` (`@ActiveProfiles("test")`) — loads `src/test/resources/application-test.yml` which switches the datasource to in-memory H2 (the prod `MySQL5InnoDBDialect` is overridden to `H2Dialect` here). It also pins `pungdong.payment.mode: stub` so the PG client never goes real even if `PAYMENT_MODE=toss` leaks from direnv (see testing.md).
- Redis: tests use an **embedded Redis server** (`com.github.codemonstur:embedded-redis:1.4.3`, the maintained arm64-compatible fork) started by `EmbeddedRedisConfig` on a **dedicated port 16379** — deliberately separate from the docker Redis (6379) so `./gradlew test` never touches the local dev cache. `application-test.yml`'s `spring.redis.port` and `EmbeddedRedisConfig.TEST_REDIS_PORT` are kept in sync at 16379. (Earlier this used a random free port via `System.setProperty`, but that static block applied *after* `RedisAutoConfiguration` bound `spring.redis.port`, so tests fell back to yml's 6379 = docker Redis and leaked stub venue caches into dev.) **Redis 인프라 전반(용도·운영·이 격리 원칙)은 [docs/architecture/redis.md](docs/architecture/redis.md); config 작업 시 [global/config/CLAUDE.md](src/main/java/com/diving/pungdong/global/config/CLAUDE.md) 가 자동 로드된다.**
- Elasticsearch: **removed in Phase 3** (2026-06-24). The former `@Profile("!test")` gate, the three Boot ES auto-config excludes in `application-test.yml`, and the `TestElasticSearchConfig` Mockito scaffold are all gone. Search is now MySQL `JpaSpecification` (`LectureSpecifications.keywordMatch` — title/instructor `LIKE`), so there is nothing to mock.
- Controller tests follow the pattern `@SpringBootTest + @AutoConfigureMockMvc + @AutoConfigureRestDocs + @Import(RestDocsConfiguration.class) [+ EmbeddedRedisConfig.class]` with services replaced via `@MockBean`. They emit REST Docs snippets — when adding a new controller test, include `document(...)` calls so the generated documentation in `src/docs/asciidoc/api.adoc` stays complete.
- The `AuthUseCaseTest` (`src/test/java/com/diving/pungdong/usecase/`) is a Phase 1 safety net of use-case scenarios that run against the real Spring Security filter chain (no `@MockBean` for auth). When changing anything in `JwtTokenProvider`, `JwtAuthenticationFilter`, `SecurityConfiguration`, or auth flow controllers, **expect this test to catch regressions**. `JwtAuthenticationFilter` rejects blacklisted tokens (the Redis `"false"` marker written by `/sign/logout`), and `L1`/`L2` verify that logout invalidates the access and refresh tokens (401 JSON). (All earlier carry-overs are now resolved — the logout no-op, the `RestDocsConfiguration` context-cache hack, and the ES test scaffold removed in Phase 3; see memory `phase_0_deferred_items`.)

## Code layout

**Mid-transition: layered → domain-based (package-by-feature).** Decided 2026-06-03 (memory `architecture-package-by-feature`). Spring is package-structure-agnostic — `@SpringBootApplication` at `com.diving.pungdong` scans everything beneath it regardless of layout. New/rewritten domains are organized **by feature** (one package holds that domain's controller + service + repo + entity + dto + a domain `CLAUDE.md`). Legacy domains still sit in the old layered packages until they're rewritten.

Settled feature packages (each auto-loads its own `CLAUDE.md` when you work in it):
- `account/` — auth/계정 (sign/email/account/profilePhoto controllers, AccountService etc., Account + Role/Gender/AuthProvider/DeviceType/FirebaseToken/ProfilePhoto/InstructorCertificate entities, `dto/`). Auth **infra** (`JwtTokenProvider`, `SecurityConfiguration`, `@CurrentUser`, `UserAccount`) lives in `global/security/`, not here — it's cross-cutting.
- `notification/` — domain events → outbox → FCM pipeline.
- `venue/` — 위치(수영장·딥풀·해양 포인트). 장소 종속 정보(입장료·시간대·이용권·정기휴무)를 1급으로. **강사 커스텀(CUSTOM·비공개·종목잠금) 위치만 BE 소유** — 공식(OFFICIAL) 수영장은 Sanity authoring(`sanity/schemas/venue.ts`), FE 가 둘을 합침. 코스가 위치를 참조(강사 availability 교차·BE 의 OFFICIAL 읽기/동기화는 후속). 도메인 개념·동기화 설계는 `docs/features/venue.md`.
- `address/` — 주소 검색 + 주소→좌표(geocoding). 외부 juso(주소기반산업지원서비스) 통합을 BE 한 곳에 가둠 — FE 는 `/address-search`·`/geocode` 경유(승인키 은닉). `AddressApiClient`(juso/stub 게이트), 좌표는 proj4j 로 한국격자→WGS84. 좌표제공 개발키 부재로 로컬 stub 기본.
- `global/` — domain-agnostic shared: `global/config/`, `global/security/`, `global/advice/`, `global/model/` (CommonResult envelope), `global/ResponseService`.

Cross-domain coupling is expected in this monolith — e.g. `Account` is imported by ~35 files in other domains (lecture/reservation reference it as creator/applicant). That's fine; just keep the dependency direction one-way (account doesn't import lecture).

**Legacy layered packages** (`controller/ service/ repo/ domain/ dto/`) — still hold lecture/reservation/schedule/review/equipment/location/lectureImage. These get folded into feature packages as each is rewritten (lecture/reservation are pending a 기획 redesign). Old description of the layered layout below still applies to these:

- `controller/` — REST endpoints, organized by feature (`account/`, `lecture/`, `schedule/`, `reservation/`, `review/`, `equipment/`, `location/`, `lectureImage/`, `profilePhoto/`, `sign/`).
- `service/` — business logic, mirroring controller features. Sub-packages exist for cross-cutting concerns: `service/image/` (S3 upload via AWS SDK v1). (`service/kafka/` was removed in Phase 2; `service/elasticSearch/` in Phase 3.)
- `repo/` — Spring Data JPA repositories. Dynamic queries use **`JpaSpecificationExecutor` + a sibling `*Specifications` utility class** (e.g. `LectureSpecifications` — `matching` for filters, `keywordMatch` for keyword search); QueryDSL was removed in Phase 0.4.
- `domain/` — JPA entities grouped by aggregate (`account/`, `lecture/`, `schedule/`, `reservation/`, `payment/`, `review/`, `equipment/`, `location/`).
- `dto/` — request/response DTOs. **Convention**: `dto/<feature>/<operation>/` (e.g. `dto/lecture/create/`, `dto/account/signUp/`). Add new DTOs to the matching operation folder; create a new one if needed.
- `config/` — Spring `@Configuration` beans (Redis, email, HTTP client, i18n message source). `config/security/` holds `SecurityConfiguration` (still extends the **deprecated** `WebSecurityConfigurerAdapter` — migration to `SecurityFilterChain` bean is scheduled with the Phase 1 auth absorption), `JwtTokenProvider`, `JwtAuthenticationFilter`, `CurrentUser` (custom `@AuthenticationPrincipal` annotation), `UserAccount` (`UserDetails` wrapper).
- `advice/` — `@RestControllerAdvice` exception handling. Custom exceptions live in `advice/exception/`; user-facing messages are looked up via `MessageSource` against `src/main/resources/i18n/exception*.yml` (configured via `yaml-resource-bundle`).
- `model/` — `CommonResult` / `SingleResult<T>` / `ListResult<T>` / `SuccessResult` envelope types returned to clients. Build them through `ResponseService`.

## Security model

JWT-based, stateless (`SessionCreationPolicy.STATELESS`). `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`. URL → role mapping is centralized in `SecurityConfiguration.configure(HttpSecurity)` — when adding a new endpoint, update the matchers there. Roles: `ADMIN`, `INSTRUCTOR`, `STUDENT` (the default for new sign-ups). Several public endpoints (lecture browsing, sign-up/login, email code, password reset, exception lookup) are explicitly `permitAll`. Inject the current user via `@CurrentUser Account` rather than reading from `SecurityContextHolder` directly.

Auth failures return **JSON `401`** directly via `CustomAuthenticationEntryPoint`; access denials return **JSON `403`** via `CustomAccessDeniedHandler` (both wired in `SecurityConfiguration.exceptionHandling`; bodies are the `CommonResult` `{success:false, code, msg}` envelope, code/msg from `MessageSource`). This replaced the old 302-redirect-to-`/exception/*` scheme (done in PR #19). `ExceptionController` now only exposes a **vestigial** `GET /exception/forbiddenToken` (throws `ForbiddenTokenException` → `ExceptionAdvice`) — it is not wired into the auth flow anymore and is a cleanup candidate.

### Client-supplied identity & object-level authorization (don't trust the client)

**Repo rule (set 2026-06-28):** the requester's identity always comes from the **session**, never a client param — and every client-supplied resource id is **ownership-verified** before acting. Full principle: [docs/architecture/security.md](docs/architecture/security.md).

- **Identity from session, never input.** The caller's own account id is read from `@CurrentUser`/JWT — **never** accepted as `@RequestParam`/`@PathVariable`/body. So FE/proxies/sniffers can't impersonate (`GET /x?userId=123` → increment to scrape others = the exact thing to prevent; Coupang removed all such params). Anything derivable from the session stays in BE logic.
- **Object-level authorization (anti-IDOR).** Client-supplied resource ids (round/order/enrollment/session id) are fine, but the handler must verify the current user owns/can-access that object before acting — else **404 (존재 숨김)** / 403. Pattern in repo: `requireMyRound`, `requirePayable`, `requireForInstructor`, `owner.getId().equals(me.getId())`. Missing check = IDOR (남의 결제 취소·남의 정보 조회).
- **Claude must apply proactively:** when adding/reviewing an endpoint, derive identity from `@CurrentUser` and add the ownership check on every id it accepts. **A signature taking an account/user id as a param is a red flag** — flag it and move identity to the session. Public-facing "fetch by id" features (e.g. Instructor Profile) use a **non-sequential handle (`nickName`)**, not account id.

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
- **Avoid `EmbeddedRedisConfig` unless the feature actually exercises Redis**: it is auto-loaded for `@SpringBootTest` and starts one shared embedded Redis on **16379** (separate from docker Redis 6379). `RedisTemplate` autowires lazily, so as long as the test path doesn't call Redis ops it's harmless. (The docker-Redis-pollution root cause is fixed by the dedicated 16379 port; Testcontainers remains a possible future upgrade.)
- **`@DisplayName` is Korean prose, prefixed with a scenario code** (e.g. `S1`, `V2`, `D1`, `L1`). Group: `S*` = success/happy-path, `V*` = validation rejection, `D*` = duplicate/conflict, `L*` = login/logout interaction, `T*` = token-related, `R*` = role/authorization. Each line reads as a sentence ending with the observable outcome.
- **One scenario = one `@Test` method**: arrange (HTTP body), act (`mockMvc.perform(...)`), assert HTTP status + assert DB state via repo. Don't squeeze multiple branches into one test.
- **`@AfterEach` cleans up persisted rows** so tests are order-independent. Don't rely on `@Transactional` rollback for `@SpringBootTest` + MockMvc — the transaction boundary is inside the controller call, not the test method.
- **Comments at class level**: a short Javadoc explaining "read the `@DisplayName` lines top to bottom = spec." Patterns to copy: `AuthUseCaseTest` (HTTP-level + filter chain), `NotificationOutboxFlowTest` (event/repo-level + lifecycle), `SignUpUseCaseTest` (HTTP → DB end-to-end).

### Memory & context handoff

The user maintains permanent project context in `~/.claude/projects/<this-repo-path>/memory/`. Read those files at session start when relevant. Active files include:

- `MEMORY.md` — index
- `user_role.md` — solo side project, Spring beginner using day-job exposure as compounding learning
- `project_simplification_plan.md` — multi-phase roadmap with launch target ~2026-06-12; Phase 0+1 done; Phase 2 in progress
- `phase_0_deferred_items.md` — three Phase 0 carry-overs, **all now resolved**: (1) logout no-op → blacklist, (2) RestDocs context-cache hack → fixed-port embedded Redis, (3) ES `@Profile("!test")` + `TestElasticSearchConfig` mock → removed in Phase 3 (ES gone)

When `application.yml` placeholders, `@Profile("!test")` annotations, or `@MockBean` on services that we can't really mock look "wrong" — check memory first. They're often deliberate.

### TypeScript API contract

API 컨슈머는 모바일 + 웹 TypeScript 클라이언트들. 그들의 단일 출처는 [`docs/api-clients/types.ts`](docs/api-clients/types.ts).

**Per-PR 규칙 (도메인 문서와 동일 원칙)**: 컨트롤러 시그니처 / 응답 필드 / 도메인 enum / 공통 envelope 을 건드리는 PR 은 같은 커밋 / PR 안에서 `types.ts` 를 갱신한다.

세부 작성 규약 (enum 표기 / HAL 응답 패턴 / 섹션 주석 / FE Claude 핸드오프 프롬프트 등) 은 [`docs/api-clients/CLAUDE.md`](docs/api-clients/CLAUDE.md) — 그 디렉토리에서 작업할 때 자동 로드되는 좁은 컨텍스트.

자동화 (springdoc-openapi) 는 출시 이후 검토 — 현재는 솔로 dev + 출시 임박이라 수동 유지.

### Sanity Studio (어드민 CMS) — `sanity/`

자격증 단체 카탈로그 + 약관 콘텐츠는 **Sanity**(호스티드 headless CMS)가 관리하고, 그 **Studio(스키마)는 이 BE 레포 [`sanity/`](sanity/) 가 소유**한다 (FE 아님 — 스키마 계약을 BE 도메인이 정하고 BE 가 `term` 을 서버사이드로 읽기 때문, 성격상 back-office). Node 폴더지만 **Gradle 빌드와 무관**하고 `pnpm deploy` 로 Sanity 클라우드에 독립 배포된다 (`sanity/node_modules` 는 gitignore, 소스+lock 은 커밋). FE 는 projectId + GROQ(`sanity/queries.ts`) 만 복사해 `@sanity/client` 로 직접 읽는다. 스키마 계약(`term` key/version/contexts, `certOrganization.code`, `disciplines`)을 바꾸면 [`sanity/CLAUDE.md`](sanity/CLAUDE.md) 의 "계약" 항목대로 consent 도메인 / `types.ts` 도 같이 점검. (결정 히스토리: 2026-06-12 FE→BE 이관, 이유는 [`docs/features/consent-and-terms.md`](docs/features/consent-and-terms.md).)

### Architectural changes update README + domain docs + feature docs

Three layers of documentation, all versioned in the repo:

1. **Root [README.md](README.md)** — single Mermaid diagram of the whole system. Update when the *system topology* changes (external dependencies, deployment shape, storage backends).
2. **[docs/architecture/<domain>.md](docs/architecture/)** — per-domain zoom-in (*어떻게* — 구현). 7-section template. Update when *that domain's* components, flow, model, or permission matrix changes.
3. **[docs/features/<feature>.md](docs/features/)** — 여러 도메인에 걸친 **피처 단위 문서** (*무엇을 / 왜* — 정책·컨텍스트·결정 히스토리). 도메인 문서가 *구현*을 다룬다면 이건 *제품 정책*을 다룬다.

When a PR materially changes layer 1 or 2, update the relevant doc(s) **in the same PR**. The docs are the orientation surface for human reviewers (the user is a Spring beginner — diagrams help them grok the change).

**피처 단위 문서 (layer 3) 는 필수 규약 (set 2026-06-10):** 한 피처가 **여러 도메인에 걸치거나** 비자명한 **정책·요구사항·구현 특이점**을 가지면, 그 **피처 개발이 완료될 때 `docs/features/<feature>.md` 를 반드시 작성/갱신**한다 (대화·결정으로만 남고 코드 도메인별로 흩어져 context 가 유실되는 걸 막는 게 목적). 피처 문서는 **"정책·왜·히스토리"를 소유**하고 **"어떻게(ER·엔드포인트)"는 도메인 문서로 링크만** — 메커니즘을 복붙하지 않아 single-source/drift 방지. 첫 예시: [강사 자격·온보딩](docs/features/instructor-onboarding.md) (discipline + identity-verification + instructor-application). 단일 도메인에 갇힌 소규모 변경은 도메인 문서/CLAUDE.md 로 충분 — 피처 문서는 *교차·정책성* 일 때.

세부 작성 규약:
- 아키텍처 도메인 문서 (7-섹션 템플릿 / Mermaid fence 균형 / 인덱스 / 톤) 은 [`docs/architecture/CLAUDE.md`](docs/architecture/CLAUDE.md).
- 피처 문서 (구조 / 역할 분담 / drift 방지) 은 [`docs/features/CLAUDE.md`](docs/features/CLAUDE.md).
둘 다 해당 디렉토리에서 작업할 때 자동 로드되는 좁은 컨텍스트.

Examples that warrant a **root README** diagram update:
- Removing Kafka (done in Phase 2-C)
- Removing Elasticsearch (done in Phase 3 — search moved to MySQL `JpaSpecification`)
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
