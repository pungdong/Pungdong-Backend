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
- [닉네임(nickname)](nickname.md) — account(형식·예약어·중복 판정) + branding(닉네임=공개 URL 식별자) + moderation(사후 신고, 사칭 사유 부재) · **문자셋 축소가 사칭 방어의 절반**(동형이의 문자 원천 차단) · 예약어 매칭 3분류(포함/접두/정확일치)와 오탐 정책(`masterdiver`) · 어드민 예외 · 형식은 알려주고 예약어 목록은 숨김 · 소급 적용 안 함
- [브랜딩 페이지 / 내 프로필(account-branding)](account-branding.md) — branding + account(닉네임=공개 URL) + instructorapplication(자격·검수 파생) + course(연결, 후속) · 강사/일반 공용(워딩만 role 분기) · 첫 쓰기 upsert · 자격 자유입력 폐기 · 영상 제외(#207)
- [게시물의 두 표면(post-surfaces)](post-surfaces.md) — community ↔ branding **관계 단일 출처** · 행 하나/화면 둘/상태 셋 · `showOnProfile`=더하기 · 숨김=전역 스위치 & 문 하나 · 관문 쿼리 비대칭 · 소유권 분할 · 새 노출 축 추가 시 체크리스트
- [커뮤니티(community)](community.md) — community + branding(게시물 테이블 공유 → 관계는 [post-surfaces](post-surfaces.md)) + instructor-application(강사 판정) + course(연결 강의) + notification(댓글 알림) · 카테고리 4종 · 참여 신청 영구 제외(=예약 플로우) · 신고 2-A · 수정 기간제한/수정됨 배지 없음
- [신고·차단(moderation)](moderation.md) — block + community(신고·피드 필터) + branding(프로필·추천 강사) · **애플 UGC 1.2 통과 조건** · 신고≠차단(판단 필요/불필요) · **BE 가 필터**(FE 필터는 페이징을 거짓으로 만든다) · 상호 은닉·무통보 · **거래 관계는 범위 밖** · 댓글 수는 스레드와 같은 기준
- [세션 단체 채팅(session-group-chat)](session-group-chat.md) — chat + availability(일정=방 단위) + enrollment(참여자격=결제완료) + notification(참여자 fan-out 푸시) · 방 PK=일정 id·FK 없음(전원 환불로 일정이 물리 삭제돼도 방 생존) · 지연 생성 · 커서 페이지네이션 + 전송 멱등 · 폴링+FCM(WS/SSE 기각) · 딥링크는 방 직행(허브 착지 규약 정정)
- [푸시 알림(push)](push.md) — notification(발송) + account(토큰) + FCM/GCP(plop-5997b) · 계약 SoT(/me/devices·data.notificationId·WIF 키리스) · BE 리드/FE 컨폼 · 인앱 알림함 #132 후속 · 메커니즘은 architecture/notification.md
- [OTA 텔레메트리·릴리스 대시보드·최소버전 게이트(ota-telemetry)](ota-telemetry.md) — ota(신규) + account(탈퇴 링크 해제) + 모바일 앱 + apps/admin · **firebase_token 확장 기각**(로그아웃/푸시거부 기기가 빠져 가장 보고 싶은 집단이 먼저 지워짐) · permitAll 수집 + installId · **관측은 관용적/제어는 엄격** · 롤백 2종 분리 · BE 는 D1 을 안 읽음 · 메커니즘은 architecture/ota.md
- [검색·생성엔진 노출(SEO/GEO)](seo-and-geo.md) — course(공개 상세 읽기 게이트·`published_at`) + branding + community + global/security · **웹 URL 은 판매 화면이기 전에 색인 자산** · **SEO(발견/재방문) vs GEO(인용) 축 구분표** · 읽기 축 ≠ 행동 축(마감 강의는 읽히지만 저장 400) · 판정은 상태가 아니라 발행 이력 · `lastmod` 는 모르면 안 낸다(무엇을 못 잡는지 표) · GEO 는 "사실을 구조로" 하나(BE 는 크롤러를 구분하지 않는다) · **새 공개 읽기 엔드포인트 BE 체크리스트 8항** · BE↔FE 소유 경계표 · FE 짝 문서 `PungDong/docs/web-seo.md`

## 톤

한글 본문, 코드/식별자 영어. 도메인 문서·메모리로 cross-link 다용. 정책은 단정적으로(이게 결정이다), 미정은 "미정/로드맵" 으로 명시.
