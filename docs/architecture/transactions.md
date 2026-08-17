# 트랜잭션 · 격리 — "내가 방금 커밋한 것도 안 보인다" (transactions)

> 크로스커팅 규약 문서. 도메인별 흐름은 각 `docs/architecture/<domain>.md`, 테스트 격리 일반론은
> [testing.md](testing.md). **같은 함정을 세 도메인에서 세 번 밟고 나서**(2026-08-12 enrollment ·
> 2026-08-13 payment · 2026-08-17 community) 한 곳에 모았다. 새로 "쓰고 → 바로 세거나 읽어서 → 응답에
> 담는" 경로를 만들 때 이 문서의 체크리스트를 먼저 본다.

## 한 줄

**MySQL(InnoDB) 기본 격리 REPEATABLE READ 에서 트랜잭션은 첫 SELECT 시점의 스냅샷을 끝까지 본다.
그 뒤에 *다른 트랜잭션* 이 커밋한 행은 — 그게 내가 `REQUIRES_NEW` 로 방금 커밋한 행이어도 — 이 트랜잭션의
plain SELECT/COUNT 엔 안 보인다.** 자기 트랜잭션이 직접 INSERT/DELETE 한 것만 보인다.

## 왜 헷갈리나 — 지문(fingerprint)

| 관찰 | 오해 | 진짜 |
|---|---|---|
| "제거는 정확한데 추가만 1 모자란다" | flush 타이밍 / 한 박자 지연 | 제거는 **자기 트랜잭션** 이 지워서 보이고, 추가는 **REQUIRES_NEW 가 커밋** 해서 안 보인다 → 비대칭이 곧 지문 |
| "정원 체크했는데 넘쳤다" (H-4) | 락 누락 | 락은 잡았어도 **count 가 락 이전 스냅샷** 을 읽었다 |
| "남이 방금 승인/환불 기록한 걸 못 봐 재청구" | 레이스 | 발행자 트랜잭션에 조인한 조회가 **발행자 스냅샷** 에 갇혔다 |
| "H2 테스트는 통과하는데 staging 에서만 틀린다" | 환경 차이 / 데이터 | **H2 기본은 READ COMMITTED** — 스냅샷 의미 자체가 다르다 |

REPEATABLE READ 자체가 잘못은 아니다(일관 읽기가 필요한 곳이 많다). 문제는 **"방금 커밋된 최신 값을 읽어야
하는 지점"** 이 스냅샷 안에 있는 것이다.

## 인시던트 3건 (같은 원인)

| 날짜 | 도메인 | 무엇이 틀렸나 | 고친 방식 | 기록 |
|---|---|---|---|---|
| 2026-08-12 | enrollment (H-4) | `requireSeat` 이 세션 행을 FOR UPDATE 로 잡고도 **plain count** 로 점유를 세서 오버부킹 — count 가 트랜잭션 앞의 course/coverage 조회 시점 스냅샷을 읽음 | 점유 count 도 **잠금 조회**(`lockOccupyingRoundIds`, FOR UPDATE) — 잠금 읽기는 스냅샷을 우회해 최신 커밋을 읽는다 | [payment.md §4 동시성 방어](payment.md), `payment/CLAUDE.md` |
| 2026-08-13 | payment | 승인/환불 **원장** 조회 가드가 발행자 트랜잭션에 조인하면 남이 방금 커밋한 시도를 못 봐 이중청구/이중환불 | 원장은 **별도 빈 + 기록·조회 모두 `REQUIRES_NEW`**(`PaymentApprovalLedger`, `RefundLedger`) | `payment/CLAUDE.md`, 두 클래스 Javadoc |
| 2026-08-17 | community | 좋아요/북마크/댓글좋아요 **POST 응답 `count` 가 내 것 빠진 값**. 삽입은 `IdempotentInsert`(REQUIRES_NEW) 가 커밋했는데 바깥 `countBy…` 가 스냅샷에 갇힘. DELETE 는 정확(자기 변경) → 비대칭 | 카운트를 **`IdempotentInsert.countFresh`(REQUIRES_NEW readOnly)** 로 새 스냅샷에서 읽음 (#280) | `community/CLAUDE.md` 함정 16, FE `PungDong/docs/features/community.md` "BE 핸드오프" |

세 번째 건은 FE 가 먼저 "응답이 한 박자 늦다" 로 오진해 count 를 버리는 우회 PR 을 냈다가 회귀(멱등 재요청 시
영구 과다계상)를 만들 뻔했다. **응답만 연달아 보면 멱등 no-op 과 stale 을 구별할 수 없다** — 독립된 읽기
경로와 대조해야 한다. 그리고 응답 `count` 는 "이번 변경이 반영된 값" 이 계약이라 FE 가 보정할 수 없다.

## 패턴 메뉴 — 언제 무엇을 쓰나

| 필요 | 패턴 | 비고 |
|---|---|---|
| 방금 커밋된 최신 값을 **읽기만** 하면 됨 (응답 count, 존재 확인) | **`REQUIRES_NEW` + `readOnly=true` 로 읽는다** — 새 트랜잭션 = 새 스냅샷 | `IdempotentInsert.countFresh`, `PaymentApprovalLedger.findApproved`. 별도 빈이어야 한다(아래) |
| 읽은 값을 근거로 **쓰기를 결정** (정원·잔액·상태 가드) | **잠금 읽기**(`SELECT … FOR UPDATE`, `lockById` / `@Lock(PESSIMISTIC_WRITE)`) — 스냅샷 우회 + 직렬화 | H-4. 잠금 대상 행이 없으면(신규) 대신 **DB UNIQUE 로 최종 심판** |
| 부수효과(외부 PG 호출·멱등 삽입)의 사실을 **발행자 롤백과 무관하게** 남김 | **`REQUIRES_NEW` 쓰기** (원장·`IdempotentInsert`) | 그 다음 읽기는 위 첫 행을 따른다 — REQUIRES_NEW 로 썼으면 REQUIRES_NEW 로 읽는다 |
| 값이 나 하나의 변경으로 결정됨 | 조회 대신 **결과로 계산**(삽입됐으면 +1) | 단순하지만 경쟁 요청이 있으면 틀린다 — 최신 커밋을 원하면 첫 행 |
| 트랜잭션 전체가 최신 커밋을 봐야 함 | 그 트랜잭션만 `@Transactional(isolation = READ_COMMITTED)` | 최후 수단. 전역 격리 변경은 하지 않는다(일관 읽기에 기대는 코드가 있다) |

### 함정 — 같이 기억할 것

- **self-invocation 이면 `REQUIRES_NEW` 는 무시된다.** 같은 클래스 안에서 부르면 Spring 프록시를 안 거친다.
  그래서 원장·`IdempotentInsert` 가 **별도 빈** 이다. 새 helper 도 별도 빈으로.
- **`REQUIRES_NEW` 는 다른 커넥션이다.** 바깥이 같은 행을 FOR UPDATE 로 잡고 있으면 안쪽이 그 행을 UPDATE 하려다
  **self-deadlock**(`payment/CLAUDE.md` 41 — 환불 `markDone`). REQUIRES_NEW 읽기(readOnly)는 잠금을 안 잡아 안전.
- **`rolloutState/헬스 초록 ≠ 값이 맞다`** 류와 같은 결로, "테스트 초록 ≠ 격리 의미가 맞다". 아래 테스트 절.
- 롤백 안 되는 커밋이 하나 늘어난 것이므로 REQUIRES_NEW 쓰기는 **"이 사실이 바깥이 실패해도 남아야 하는가"** 로만
  정당화한다(원장 = 예, 카운터 캐시 = 아니오).

## 체크리스트 — "누르면 최신 값을 돌려주는" API 만들 때

좋아요·북마크·찜·저장·참여·팔로우 처럼 **UNIQUE 로 멱등인 삽입 + 응답에 count/상태** 인 엔드포인트가 대상이다.
(2026-08-17 현재 `IdempotentInsert` 사용처는 community 3곳뿐이지만, 강의 저장/찜 류를 만들면 똑같이 걸린다.)

1. 삽입은 `IdempotentInsert`(또는 동형의 REQUIRES_NEW 별도 빈)로 — 제약 위반을 **바깥에서** 잡는다
   (`community/CLAUDE.md` 16).
2. 응답의 count/존재는 **`countFresh` 류 REQUIRES_NEW 읽기** 로. plain `countBy…` 금지.
3. 제거(DELETE) 경로는 자기 트랜잭션 변경이라 plain 이어도 정확 — 그래도 **양쪽 대칭으로 테스트** 한다
   (비대칭이 생기면 그게 지문이다).
4. 멱등 재요청(이미 눌린 상태로 POST)은 값이 **안 변하는 게 정상** — 테스트에 같이 잠근다.
5. 응답 계약 주석에 "**이번 변경이 반영된 값**" 이라고 명시한다 — FE 는 이 값을 그대로 쓰고 보정하지 않는다.
6. 테스트는 아래 규약대로 **격리 수준을 실제와 맞춘다**. H2 기본으로 초록이면 아무것도 증명 안 된 것.

## 테스트 규약 — H2 는 이 버그를 못 잡는다

- **H2 기본 격리 = READ COMMITTED.** 커밋된 행이 즉시 보여서 위 버그가 전부 초록으로 통과한다
  (community K1/C6 이 그랬다).
- 선택지 둘, 강한 쪽부터:
  1. **실 MySQL 하네스** `./gradlew mysqlTest` (Testcontainers, `@Tag("mysql")`, `src/test/java/com/diving/pungdong/concurrency/`)
     — FOR UPDATE·스냅샷·데드락 의미까지 그대로. 동시성(H-1/H-4)은 여기서만 검증된다. 기본 스위트에서 제외라
     **로컬 Docker 필요**.
  2. **컨텍스트별 격리 핀** — 해당 테스트 클래스만
     `@SpringBootTest(properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ")`
     (`CommunityReactionCountUseCaseTest`). 스냅샷 가시성 하나만 재현하면 될 때 가볍다.
     ⚠️ **전역(`application-test.yml`)으로 올리지 말 것** — H2 2.1.214 가 REPEATABLE READ + `ON DELETE CASCADE`
     삭제에서 내부 NPE(50000)를 낸다(2026-08-17 시도·철회).
- 재현 테스트의 형태: **"응답 값 == 독립 경로로 읽은 실제 값"** 대조. 응답끼리 비교하면 멱등 no-op 을 못 가른다.

## 관련

- [payment.md](payment.md) §4 "동시성 방어" — 원장·잠금 count·DB 제약 최종 심판, `mysqlTest`
- [community.md](community.md) §3-1 카운터 비저장 집계, `community/CLAUDE.md` 함정 16
- [testing.md](testing.md) — hermetic 원칙(외부 경계). 이 문서는 그 안쪽, DB 의미의 격리
- 코드: `community/IdempotentInsert`, `payment/PaymentApprovalLedger`, `payment/RefundLedger`,
  `enrollment/EnrollmentService.requireSeat`, `test/.../usecase/CommunityReactionCountUseCaseTest`,
  `test/.../concurrency/MySqlConcurrencyTestBase`
- FE 측 기록: `PungDong/docs/features/community.md` 2026-08-17 결정 로그 + "BE 핸드오프" 절
