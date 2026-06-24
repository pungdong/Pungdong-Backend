# 런칭 토글 · 데모(seeded) 콘텐츠

> **피처 문서** — 정책·왜·히스토리를 소유. 구현(엔드포인트·필터·게이트)은 도메인 문서로 링크만.

## 한 줄

정식 강사를 모으기 전, 데모(샘플) 강의/투어로 화면을 채워 강사들이 보고 따라 만들게 유도하고, **예약은 막아둔 채**("7월 정식 런칭을 기다려주세요") 운영한다. 실강사 코스가 충분히 차면 **무배포로** 데모를 가리고 예약을 연다. 두 스위치(`launched`/`showSeededCourses`)는 **Sanity 싱글톤**에 있어 FE/BE 양쪽이 런타임에 읽고, 값 하나 바꿔 publish 하면 재배포 없이 런칭된다.

## 협력 도메인

| 도메인 | 구현 | 역할 |
|---|---|---|
| (설정) Sanity `siteSettings` | [sanity/schemas/siteSettings.ts](../../sanity/schemas/siteSettings.ts) | `launched`·`showSeededCourses` 싱글톤 (무배포 토글의 단일 출처) |
| global/sitesettings | `HttpSanitySiteSettingsProvider` (캐시·fail-safe) | BE 가 싱글톤을 서버사이드로 읽는 경계 (consent `HttpSanityTermClient` 패턴) |
| course | [course-discovery.md](course-discovery.md) | `Course.seeded` 데이터 표식 · 둘러보기/상세 데모 필터 |
| enrollment | [booking.md](booking.md) | `launched=false` 면 신청 전역 차단(`PreLaunchException`, code -1016) |

## 정책 (requirements)

### 두 개의 독립 스위치 — 전역(launched) vs 코스별(seeded)

| | `launched` (Sanity, 전역) | `seeded` (BE DB, 코스별) |
|---|---|---|
| 적용 대상 | **모든 코스** (실강사 것 포함) | **데모 코스만** |
| 효과 | 신청 차단(BE 403) + FE 런칭대기 배너 | FE "샘플용" 태그 + `showSeededCourses=false` 시 노출 제외 |

둘은 **독립**이라 시점별로 조합된다:

| 시점 | launched | showSeededCourses | 결과 |
|---|---|---|---|
| 런칭 전(현재) | false | true | 데모=샘플태그+신청막힘 / 실강사코스=태그없음+신청막힘 (둘 다 공개·열람 가능) |
| 정식 런칭 | true | false | 데모는 목록/상세에서 사라짐(데이터는 보존) / 실강사코스 신청 개방 |

### 핵심 결정

- **데이터 ↔ 노출 분리.** 런칭 시 데모를 **삭제하지 않고 가린다**(`seeded` 플래그 + `showSeededCourses`). 되돌릴 수 있고, 실데이터와 안전하게 공존.
- **`launched`는 전역 게이트.** 런칭 전엔 실강사가 미리 올린 코스도 **공개는 하되 신청만 막는다**(사용자 결정: "유저에겐 보이게, 신청만 못하게"). 카탈로그가 차오르는 게 보이는 콜드스타트 전략.
- **서버사이드 이중 차단.** FE 배너만 믿지 않고 BE 도 `POST /enrollments` 를 거부(`PreLaunchException` → 403, code `-1016`). FE 는 이 코드로 런칭대기 안내 분기.
- **`seeded` 는 클라이언트가 못 정한다.** 정식 작성 API(`CourseCreateRequest`)엔 `seeded` 필드가 없어 정상 경로에선 항상 false. 데모 시더만 DB 에 직접 박는다(권위 = 시더).
- **무배포 토글 = Sanity.** env/config 에 두면 플립에 재배포가 필요하다. 이미 FE 는 Sanity CDN 을 직접 읽고 BE 는 약관을 서버사이드로 읽으므로(레포 Sanity 읽기 기조), 싱글톤 1개면 양쪽 무배포. BE 는 짧은 TTL(기본 60초) 캐시 + **fail-safe**(도달 불가 시 마지막값/`SAFE_DEFAULT`=미런칭+데모노출 — 사고로 신청이 열리지 않게 보수적).

## 결정 히스토리 (왜 이렇게 됐나)

- **2026-06-23 데모→정식 전환 설계.** 공모전 데모(강사6·강의6·투어5, PR #76)가 반응이 좋아 정식 기능화. 데모 데이터는 "클렌징하면 끝"인 임의 생성 콘텐츠라 판단 → 삭제 대신 `seeded` 표식 + 노출 토글로 수렴.
- **BE 플래그 vs Sanity 비교 후 Sanity 채택**(전역 스위치 한정). 데모 *코스 본문*은 실 Course 와 동일 경로로 렌더돼야 "따라 만들기" 목표에 맞으므로 BE Course 로 유지(Sanity 복제 안 함, drift 방지). 반면 *런칭/노출 스위치*는 무배포 토글이 핵심이라 Sanity 싱글톤.
- **본인확인·AI 안전진단은 별개.** 데모 강사는 본인확인 stub(`StubIdentityVerifier`)·가짜 PII·가짜 자격증으로 만들어졌고(공개 노출 안 됨), AI 안전진단은 BE 발자국이 아예 없다(FE 모킹). 둘 다 이 피처(클렌징/토글) 밖 — 정식화 시 별도 실연동 필요.

## 미해결 / 후속

- **데모 시더 비파괴화** — 시더(PR #76)는 전체 TRUNCATE 방식이라 실강사 코스와 공존 불가. `seeded` 컬럼 도입 후 **데모 소유 행만 정리 + 생성 후 seeded=1 표시**로 교체해야 한다(이 PR 의 즉시 후속).
- 런칭 후 데모 **완전 삭제 정책**(가리기만 vs 일정 후 purge) 미정.
- 세그먼트 backfill(종목/지역별 실코스 < N 일 때만 데모 노출) — 현재는 전역 on/off. 필요 시 후속.
