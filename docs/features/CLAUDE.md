# CLAUDE.md — 피처 문서 (docs/features)

이 디렉토리는 **여러 도메인에 걸친 피처의 정책·컨텍스트·결정 히스토리**를 묶는다. 작업 디렉토리가 이 폴더면 이 파일이 자동 로드. 전체 컨벤션은 루트 [CLAUDE.md](../../CLAUDE.md) 의 "Architectural changes update README + domain docs + feature docs".

> **언제** 만드는지(규칙)는 루트 CLAUDE.md. 이 파일은 **어떻게 쓰는지**(구조·역할분담).

## 역할 — 도메인 문서와 무엇이 다른가

| | docs/architecture/<domain>.md | **docs/features/<feature>.md** |
|---|---|---|
| 관점 | 구현 (*어떻게*) | 정책·제품 (*무엇을 / 왜*) |
| 단위 | 코드 패키지(도메인) | 사용자에게 보이는 피처 (도메인 여러 개 걸칠 수 있음) |
| 담는 것 | ER · 엔드포인트 · 컴포넌트 맵 · 권한 매트릭스 | 정책/규칙 · 결정 히스토리(타임라인) · 로드맵 · 협력 도메인 링크 |

## 단일 출처 / drift 방지 (핵심)

피처 문서는 **"정책·왜·히스토리"를 소유**한다. **"어떻게"(ER·엔드포인트·필드)는 도메인 문서로 링크만** 하고 **복붙하지 않는다** — 메커니즘을 두 곳에 쓰면 drift 난다. 구현이 바뀌면 도메인 문서를 고치고, 정책이 바뀌면 피처 문서를 고친다.

## 권장 구조

```
# <피처명> (영문 slug)
> 피처 문서임을 명시 + 역할 분담(정책 소유 / 구현은 링크) 한 줄

## 한 줄            — 이 피처가 무엇인가
## 협력 도메인       — 표: 도메인 → 구현 문서 링크 + 역할
## 정책 (requirements) — 영역별 규칙 (자명하지 않은 것 위주)
## 결정 히스토리      — 타임라인 표 (시점 · 결정 · PR). "왜" 는 도메인 CLAUDE.md 와 중복 최소화
## 미해결 / 확장      — 로드맵, 🔴🟡🟢 심각도
## 관련 메모리        — ~/.claude 메모리 포인터
```

## 작성 시점

- **피처 개발 완료 시 (PR 머지 전후)** 작성/갱신 — 결정이 대화로만 남지 않게.
- 이후 그 피처의 **정책·규칙·확장 결정이 추가되면 이 문서 한 곳**에 누적. 구현 세부 변경은 도메인 문서.

## 인덱스

- [강사 자격·온보딩](instructor-onboarding.md) — discipline + identity-verification + instructor-application
- [휴대폰 본인인증(identity-verification)](identity-verification.md) — identity-verification + consent + Sanity term (다날 SMS·포트원 REST v2·CI/DI 암호화·무만료·CPID 개통 후속)
- [동의·약관](consent-and-terms.md) — consent + Sanity term (회원가입/본인확인/강사신청/결제 공통)
- [위치(venue)](venue.md) — venue + discipline + instructor-application (장소 종속 정보 · 정식/커스텀 · availability 교차 예정)
- [코스 작성(course-create)](course-create.md) — course + venue + venue.equipment + discipline + Sanity 자격증 (강사 강의 개설 · 위치/장비 참조 모델)
- [코스 둘러보기(course-discovery)](course-discovery.md) — course + venue + discipline (수강생 메인 홈 공개 조회·검색·필터 · 지역 광역 묶음/주소 파생)
- [강사 가용시간 캘린더(instructor-availability)](instructor-availability.md) — availability + account + instructor-application + venue (2층 모델 · 외부/수동 점유 · enrollment 후속)
- [수강신청(booking)](booking.md) — enrollment + availability + venue + course (교집합 = 학생 선택지 · exact-match join · 강사 수락/거절 · 결제는 payment 로)
- [결제(payment)](payment.md) — payment + enrollment + course + venue (토스 결제위젯 v2 · 수락→결제→확정 · 서버 권위 금액 · stub/toss · webhook 후속)
- [수강생 강의일정 hub(student-schedule)](student-schedule.md) — enrollment 그룹핑 read 허브(GET /enrollments/mine/schedule) · 강의 7/회차 9상태 중 buildable 5 파생 · 설계↔BE 갭/로드맵(메모·채팅·일정변경·환불·완료·리뷰·자격증 미구현)
- [강사 수강관리(instructor-enrollment-management)](instructor-enrollment-management.md) — enrollment 강사 거울 hub(GET /instructor/enrollments/hub) · 거래=수강생×강의 · 강사 시점 상태/플래그 파생 · 액션은 accept/reject/propose/complete 재사용 · 채팅/다이브로그 미구현
- [푸시 알림(push)](push.md) — notification(발송) + account(토큰) + FCM/GCP(plop-5997b) · 계약 SoT(/me/devices·data.notificationId·WIF 키리스) · BE 리드/FE 컨폼 · 인앱 알림함 #132 후속 · 메커니즘은 architecture/notification.md

## 톤

한글 본문, 코드/식별자 영어. 도메인 문서·메모리로 cross-link 다용. 정책은 단정적으로(이게 결정이다), 미정은 "미정/로드맵" 으로 명시.
