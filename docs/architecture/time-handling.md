# 시간 처리 (time handling) — 글로벌화 설계

> 크로스도메인 규약. "**절대시각(instant)은 UTC로 저장·연산, 표시는 뷰어 로케일로 FE가 변환. 장소-현지 벽시계(local/civil)는 변환하지 않는다.**" 일본(+9)·이후 글로벌 확장 대비. 결정일 2026-07-10.

## 1. 핵심 규칙 — 필드는 두 종류다

시간 값은 성격이 정반대인 두 부류이고, 취급이 다르다. **분류는 Java 타입으로 거의 기계적으로 갈린다:**

| 부류 | Java 타입 | 의미 | 처리 |
|---|---|---|---|
| **instant** | `OffsetDateTime`(목표) / 現 `LocalDateTime` | 타임라인 위 한 점 — "일어난 순간"(createdAt, paidAt, verifiedAt) | **UTC로 저장·직렬화**(오프셋 붙음), FE가 **뷰어 TZ로 변환** |
| **local (civil)** | `LocalDate` + `LocalTime` | 특정 **장소의 벽시계** — "잠실 14:00 수업", venue 운영시간 | **오프셋 없이 그대로**, FE는 **변환 금지**(civil 렌더) |

> ⚠️ **local을 절대시각처럼 다루면 조용히 틀린다.** 서울 14:00 수업을 뷰어 TZ로 변환하면 뉴욕에서 새벽 1시로 보인다. 이런 필드는 UTC/offset을 **붙이면 안 된다.**

예외 1건: legacy `Review.writeDate`(`LocalDate`) — 의미는 instant("작성 시각")인데 날짜만 있어 offset 불가 → local 표시용으로 두거나 엔티티를 `LocalDateTime`으로 승격 후 instant 처리(legacy, 우선순위 낮음).

## 2. 저장/직렬화 결정 (BE)

- **타입: `OffsetDateTime`, UTC로 통일.** (`Instant`도 등가지만, 코드베이스에 이미 `payment.approvedAt`가 `OffsetDateTime`(토스 반환)이라 통일 + 명시적 offset이 self-documenting.) 저장/연산은 항상 UTC offset(`+00:00`), 표시 offset 변환은 FE.
- **DB 컬럼**: `LocalDateTime→DATETIME`(naive) 에서 **`TIMESTAMP`**(MySQL이 UTC로 정규화) 로. **커넥션 TZ를 UTC**로 맞춰야 함 — 현재 datasource URL `serverTimezone=Asia/Seoul` → `connectionTimeZone=UTC`(또는 동등) 로 손봐야 조용한 9h 밀림을 막는다.
- **부수 효과(좋음)**: instant가 offset-aware가 되면 `LocalDateTime.now()` 의존이 사라져 [#166의 전역 `TimeZone.setDefault(Asia/Seoul)` 밴드에이드](../../src/main/java/com/diving/pungdong/PungdongApplication.java)를 instant 한정 걷어낼 수 있다. local(`LocalDate`/`LocalTime`)은 애초에 TZ 무관.
- **직렬화**: `OffsetDateTime` → Jackson 기본 ISO-8601 + offset → FE `new Date()` 자동.
- **기존 데이터 주의**: #166 배포 전 컨테이너는 UTC라 옛 row의 DATETIME은 UTC-wall, #166 후 새 row는 KST-wall — 같은 컬럼에 9h 다른 의미가 섞여 있다. UTC 이관 시 옛 값 해석이 애매 → **pre-launch + 대부분 데모라 수용(또는 데모 reseed)**, 정밀 마이그레이션은 과함.

## 3. 표시(display) 전략 — TZ는 FE가, 그런데 어느 TZ로?

BE는 instant를 UTC로만 준다. **"어느 TZ로 보여줄까"는 FE 결정이고, 단일 BP가 없는 제품 결정이다.** 세 전략:

| 전략 | 방식 | 언제 적합 |
|---|---|---|
| **A. 기기 TZ(자동)** | `Intl`/`toLocaleString`이 기기 TZ 사용. 여행 시 자동 이동 | 대부분 consumer 앱의 기본. 브라우저·RN 동일(둘 다 JS) |
| **B. 유저 설정 TZ** | 유저가 지역 고정(예 Asia/Seoul), 기기 무관 렌더 | 지역-앵커 커머스(쿠팡 = 쇼핑 지역 기준). 추가 상태·설정 필요 |
| **C. 콘텐츠-앵커(venue-local)** | 변환 안 함, 그 장소 현지 시각 그대로 | **우리 슬롯/venue 시간** — 물리적으로 그 장소에 감 |

**우리 스탠스 (2026-07-10, 권장):**
- **instant(createdAt·paidAt·respondedAt 등) → 전략 A(기기 TZ).** 가장 덜 놀랍고 추가 상태 0. `Intl.DateTimeFormat`/`toLocaleString`이 브라우저·RN 양쪽에서 기기 TZ로 자동 처리 → FE 무추가.
- **슬롯/venue 시간 → 전략 C(venue-local).** 이미 §1의 local. 물리적으로 그 venue에 가는 시간이라 뷰어 TZ로 바꾸면 안 됨.
- **전략 B(유저별 TZ 설정)는 지금 안 만든다(YAGNI).** 쿠팡의 유저별 지역 처리는 *지역-앵커 커머스*에 맞는 BP지 보편은 아님. 우리는 유저가 대개 자기 다이빙 지역 안에서 활동 → 기기 TZ로 충분. KR/JP **market/region 개념**이 생기면(보는 venue·통화·언어) 그때 일부 표시를 market TZ에 앵커할지 재검토 — 단 슬롯은 그때도 venue-local.

> 정리: **"나라 옮기면 그 위치 기준으로"가 instant의 기본(전략 A)**, 슬롯은 위치와 무관하게 **venue 현지 고정(전략 C)**. "한국 설정이면 KST 고정"(전략 B)은 지역-앵커가 강한 제품에서만 값어치가 있고, 지금 우리에겐 과함.

## 4. 필드 인벤토리 (source of truth)

### instant — UTC/offset으로 (변환 대상, 22개)

| 도메인 | 응답 DTO.필드 |
|---|---|
| course | `CourseCard.createdAt`, `Course.createdAt`, `Course.updatedAt` |
| consent | `MyConsent.agreedAt` |
| identity-verification | `IdentityVerificationSent.otpExpiresAt`, `MyIdentityVerification.verifiedAt` |
| instructor-application | `Detail.{createdAt,submittedAt,reviewedAt}`, `Summary.submittedAt`, `My.{submittedAt,reviewedAt}` |
| payment | `PaymentConfirm.approvedAt` — **이미 `OffsetDateTime`(목표 형태 템플릿)** |
| enrollment | `EnrollmentResponse.{createdAt,respondedAt}`, `InstructorEnrollment.createdAt`, `ScheduleHub.{createdAt,respondedAt}`, history `changedAt` |
| venue | `Venue.createdAt`, `Venue.updatedAt` |

### local — 그대로 유지 (변환 금지, ~55개) ★위험군

`LocalDate`/`LocalTime` 슬롯·운영시간. 서울 14:00은 모든 뷰어에게 14:00:

- **availability**: `AvailabilitySessionResponse.{date,startTime,endTime}`, `CoverageRangeResponse.{date,startTime,endTime}`
- **enrollment 슬롯/블록**(모든 DTO): `EnrollmentResponse.{date,blockStart,blockEnd}`(+`previousSlot`), `InstructorEnrollmentResponse`, `EnrollmentOptionsResponse`(Slot), `ProposedSlot`, `PastSlot`, `ScheduleHubResponse`(slot), `InstructorScheduleHubResponse`(upcoming+proposed)
- **venue 운영시간**: `VenueResponse`→Daypart `.{openStart,openEnd}`, →TimeBlock `.{startTime,endTime}`
- **legacy**(lecture/reservation/schedule, types.ts 미포함): 모든 `lectureTime`, `Reservation*.{date,time/reservationDate}`, `ScheduleDetail`/`ScheduleDateTimeInfo.{date,startTime,endTime}`

## 5. 미룬 것 (후속)

- **venue-TZ 앵커** — local 시간의 "어느 zone의 14:00" 명시. 지금 전부 암묵 KST이고 **JST=KST=+9**라 일본까진 안 터짐. +9 밖(미국·유럽) 확장 시 venue에 TZ 필드 추가.
- **유저별 TZ 설정(전략 B)** — market/region 개념 생기고 필요 증거 있을 때.
- **`Review.writeDate` 처리** — legacy review 손댈 때.

## 6. 결정 로그

| 시점 | 결정 | 왜 |
|---|---|---|
| 2026-07-10 | instant = `OffsetDateTime`/UTC, local = `LocalDate`/`LocalTime` 유지 | 절대시각/civil 구분이 표준. 타입으로 기계적 분류 |
| 2026-07-10 | 표시 = instant는 기기 TZ, 슬롯은 venue-local, 유저별 TZ 설정 미도입 | 단일 BP 없음 — 우리 제품(위치-앵커)엔 기기 TZ+venue-local이 최소·충분 |
| 2026-07-10 | 기존 데이터 정밀 마이그레이션 안 함 | pre-launch + 데모 위주 |

관련: [enrollment.md](enrollment.md)·[venue.md](../features/venue.md)(local 시간 소유), [identity-verification.md](identity-verification.md)(otpExpiresAt), memory `feedback_container_tz_localdatetime`(#166 TZ 밴드에이드).
