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

**우리 스탠스 (2026-07-10 확정):** 우리는 **예약 중계 앱**이라 시각이 **영수증 성격**이 강하다 — 기록은 안 흔들려야 한다. 그래서 성격별로:
- **거래성/기록 시각(신청·결제·취소 등) → venue(예약 대상) TZ + 라벨** (전략 C 앵커). 유저는 자기 마켓 venue만 예약하므로 venue TZ ≈ 유저 마켓 TZ이고, **유저가 미국으로 여행 가도 그 예약 기록 시각은 안 흔들린다**(KST 고정 + "KST" 라벨). 오늘은 전 venue 한국이라 device TZ와 화면 차이 0 → **비용 없이 미래만 대비**.
- **슬롯/venue 운영시간 → venue-local(전략 C).** 이미 §1의 local. 물리적으로 그 venue에 가는 시간이라 뷰어 TZ로 바꾸면 안 됨.
- **저관심/대화성 시각(정렬·"3분 전") → 기기 TZ(전략 A)** 로 충분. 라벨까지 안 붙임.
- **전략 B(유저별 TZ 설정)는 안 만든다(YAGNI).** 쿠팡의 유저별 지역 처리는 *지역-앵커 커머스* BP지 보편 아님. 우리 중요 시간은 전부 venue-앵커라 유저 TZ 논쟁에서 자유롭다.

> 정리: **거래성 시각 = venue TZ 고정+라벨(영수증 안정), 슬롯 = venue-local, 저관심 = 기기 TZ.** 셋 다 **BE는 instant를 UTC OffsetDateTime 하나로 주면 끝** — 표시 TZ는 FE가 venue TZ(§5의 `venue.timeZone`)로 렌더. "한국서 본 신청시각이 미국서 달라 보이나?" → **우리 앱에선 안 바뀌는 게 맞다**(거래 기록이니 venue TZ 고정).

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

## 5. venue TZ 관리 — 나라/도시 선택 (일본 확장 시, ~12월)

거래성 시각(§3)·정책 계산(환불 마감)의 앵커 = **`venue.timeZone`**(IANA zone id 문자열, 예 `"Asia/Seoul"`). **어떻게 정하나 = venue 생성 시 나라/도시 선택** (lat/lng 유도 아님):

- **나라/도시 선택 = 이중 목적** — (1) **TZ 확정**(그 자체로, geocoding 무관), (2) **나라별 주소검색 API 라우터**(한국 juso / 일본 provider / 미국 Google 등). lat/lng은 **지도 핀 용**으로 그 provider가 반환. 역할 분리: 나라선택→TZ+provider, lat/lng→지도.
- **DST는 IANA zone 이름이 처리** — `"America/New_York"` 저장하면 java.time이 날짜별 EDT/EST 자동. **offset(`-05:00`) 저장 금지**(여름에 틀림). 세분화 규칙 = "하나의 IANA zone으로 떨어질 만큼"(단일-TZ 국가=나라만, 멀티-TZ 국가=나라+도시). → **DST를 사람이 이해할 필요 없음.**
- **lat/lng→TZ 경계 데이터셋 lib 는 안 씀** — 나라선택이 TZ를 직접 주니 불필요. (멀티-TZ 국가에서 도시 대신 좌표로 정밀화하고 싶을 때만 후속 고려.)
- **지금 안 만드는 이유** — 전 venue 한국(=`Asia/Seoul`)이라 정할 대상이 없고, 나라·좌표가 보존돼 **언제든 복구 가능**(§6의 "되돌릴 수 있으면 미룸" 기준). 오늘 FE는 KST 표시로 충분. **일본 venue 생성 플로우(~12월)와 한 몸**으로 서면 됨.

### 그 외 미룬 것 (일본 확장 인벤토리 — GitHub 이슈로 추적)
- **거래성 시각을 venue TZ+라벨로 표시** (FE) — §3.
- **환불 등 시간정책 계산이 venue TZ 사용** — 수업시각(venue-local) → 절대 UTC 변환에 venue TZ 필요(유저 TZ 아님). 지금 암묵 KST.
- **`MarketingSendWindow` 하드코딩 `Asia/Seoul`** → 수신자(유저) TZ. 조용시간은 수신자 현지 기준.
- **`Review.writeDate`(legacy)** — instant 승격 or local 표시.

## 5b. 지금 하는 것 (이 작업의 유일한 "now")

**instant 22필드 `LocalDateTime → OffsetDateTime`/UTC** — 이것만. naive `LocalDateTime`은 정보가 **되돌릴 수 없이 손실**(UTC-wall vs KST-wall)이라 지금 이관이 급하다(§6 기준). 나머지(venue TZ·나라선택·표시)는 전부 복구 가능 → 일본 때.

## 6. 결정 로그

| 시점 | 결정 | 왜 |
|---|---|---|
| 2026-07-10 | instant = `OffsetDateTime`/UTC, local = `LocalDate`/`LocalTime` 유지 | 절대시각/civil 구분이 표준. 타입으로 기계적 분류 |
| 2026-07-10 | 표시 = **거래성 시각은 venue TZ+라벨**, 슬롯은 venue-local, 저관심은 기기 TZ, 유저별 TZ 설정 미도입 | 예약중계=영수증 성격 → 기록은 안 흔들려야. venue TZ ≈ 마켓 TZ라 여행에도 안정 |
| 2026-07-10 | venue TZ 결정 = **생성 시 나라/도시 선택**(lat/lng 유도 아님). DST는 IANA zone 이름이 처리 | 나라선택 = TZ + 주소검색 provider 라우터(이중목적). 일본 lat/lng 불확실 → 좌표 의존 회피. lat/lng은 지도용 |
| 2026-07-10 | **지금은 instant→UTC만. venue TZ/나라선택/표시는 일본 확장(~12월) 인벤토리** | naive LocalDateTime만 되돌릴 수 없이 손실 → 급함. 나머진 나라·좌표 보존되어 복구 가능 |
| 2026-07-10 | 기존 데이터 정밀 마이그레이션 안 함 | pre-launch + 데모 위주 |

관련: [enrollment.md](enrollment.md)·[venue.md](../features/venue.md)(local 시간·venue.timeZone 소유 예정), [identity-verification.md](identity-verification.md)(otpExpiresAt), memory `feedback_container_tz_localdatetime`(#166 TZ 밴드에이드) · `globalization_when_to_abstract`(되돌릴 수 없으면 지금·아니면 목록화). **일본 확장 TODO = GitHub 이슈 "글로벌 확장 시 고려사항".**
