# 도메인별 아키텍처 문서

전체 시스템 그림은 [루트 README](../../README.md#아키텍처-현재-상태) 의 Mermaid 다이어그램. 5가지 관점(토폴로지·요청계층·도메인맵·핵심 유스케이스·알림 파이프라인·Sanity 읽기)을 모은 발표용 상위 문서는 [system-overview.md](system-overview.md). 이 디렉토리는 그 안의 **개별 도메인** 을 한 단계 줌인해서 본다.

> **여러 도메인에 걸친 "피처" 의 정책·히스토리** 는 [`docs/features/`](../features/) 에 별도로 묶는다 (이 디렉토리는 *구현/도메인* 관점, 피처 문서는 *제품 정책/왜* 관점 — 메커니즘은 여기로 링크). 예: [강사 자격·온보딩](../features/instructor-onboarding.md) = discipline + identity-verification + instructor-application.

각 문서는 같은 틀:

1. **한 줄 요약** — 이 도메인이 뭘 하나
2. **컴포넌트 지도** — 도메인 안의 클래스 + 외부 의존
3. **핵심 흐름** — 1~2개 시퀀스 다이어그램 (HTTP → DB 까지)
4. **데이터 모델** — 관련 엔티티 ER 스케치
5. **보안 / 권한** — 엔드포인트별 인증 / 권한 매트릭스
6. **확장 자리** — 향후 PR 에서 채울 빈칸 (예: OAuth)
7. **더 깊게: use-case 테스트로 보기** — 실제 동작은 항상 `usecase/` 의 테스트가 단일 출처

## 도메인 인덱스

| 도메인 | 문서 | 상태 |
|---|---|---|
| 회원가입 + 로그인 (sign-up) | [sign-up.md](sign-up.md) | ✅ |
| 알림 (notification outbox + FCM) | [notification.md](notification.md) | ✅ |
| ~~강의 (lecture)~~ | [lecture.md](lecture.md) | 🗑️ **삭제됨 (2026-08-15)** — v1 스택 제거. 후신 [course.md](course.md). 문서는 기록으로만 보존 |
| ~~일정 (schedule)~~ | [schedule.md](schedule.md) | 🗑️ **삭제됨 (2026-08-15)** — 후신 [availability.md](availability.md) + [enrollment.md](enrollment.md) |
| ~~예약 (reservation)~~ | [reservation.md](reservation.md) | 🗑️ **삭제됨 (2026-08-15)** — 후신 [enrollment.md](enrollment.md) + [payment.md](payment.md) |
| ~~후기 (review)~~ | [review.md](review.md) | 🗑️ **삭제됨 (2026-08-15)** — v2 후신 **없음**(후기 기능 미재구현, 백로그) |
| 본인확인 (identity-verification) | [identity-verification.md](identity-verification.md) | ✅ (계정 공유 자산 · 휴대폰 SMS 2단계, 포트원/다날 · 실 라이브는 CPID 개통 후) |
| 종목 (discipline) | [discipline.md](discipline.md) | ✅ (BE 테이블 · requiresCertification) |
| 강사 신청 (instructor-application) | [instructor-application.md](instructor-application.md) | ✅ (본인확인은 identity-verification 도메인 참조) |
| 동의 / 약관 (consent) | [consent.md](consent.md) | ✅ (Sanity authoring + BE 박제 · 동의 이력) |
| 위치 (venue) | [venue.md](venue.md) | ✅ (정식/커스텀 · builder 통합 머지 · Sanity 동기화 · 장비 equipment extension) |
| 코스 (course) | [course.md](course.md) | ✅ (강사 작성 · 회차/추가세션 · 위치 venueRefId 참조 · 장비 합성 · 공개조회 후속) |
| 주소·위치정보 (address) | [address.md](address.md) | ✅ (juso 검색+좌표변환 BE 경유 · 로컬 stub · 좌표계 검증 후속) |
| 브랜딩 페이지 (branding) | [branding.md](branding.md) | ✅ (계정당 1개 공개 프로필 · 공개 URL=닉네임 · 조회는 생성 안 함 · 자격/검수는 읽기 시점 합성 · 게시물은 후속) |
| 커뮤니티 (community) | [community.md](community.md) | ✅ (게시물 테이블을 branding 과 **공유** · 노출은 브랜딩→커뮤니티 단방향 · 카운터 비저장 일괄집계 · 1-depth 댓글 · 신고 어드민 큐) |
| 강사 가용시간 (availability) | [availability.md](availability.md) | ✅ (가용시간 window + 외부/수동 점유 hold · 5상태 파생 · enrollment 연동됨) |
| 세션 단체 채팅 (chat) | [chat.md](chat.md) | ✅ (일정 1개 = 방 1개 · **방 PK=일정 id, FK 없음**(일정이 물리 삭제돼도 방 생존) · 상태 파생 · 커서 페이지네이션 + 전송 멱등 · 폴링+FCM) |
| 수강신청 (enrollment) | [enrollment.md](enrollment.md) | ✅ (booking — availability ∩ venue 교집합 · exact-match join · 강사 수락 → 결제대기 → 확정) |
| 결제 (payment) | [payment.md](payment.md) | ✅ (토스페이먼츠 결제위젯 v2 · 수락→결제→확정 · 서버 권위 금액 · stub/toss · webhook 후속) |
| 학생 보유 자격증 (certificate) | [certificate.md](certificate.md) | ✅ (프로필 "내 자격증" · 사진=비공개 PII presigned · source/holderName/강사 서버 파생 · 표시명 스냅샷 · 수정=PUT 전면교체(사진만 "생략=유지") · 강사신청 자격증과 별개) |
| **Redis (인프라)** | [redis.md](redis.md) | ✅ (도메인 아님 — JWT 블랙리스트·이메일 코드·venue 캐시 · ⚠️ 테스트 16379 격리 원칙) |
| **배포 전략 (인프라/프로세스)** | [deployment.md](deployment.md) | ✅ (도메인 아님 — 트렁크 브랜치·build-once/promote·env 격리·피처플래그·prod 수동게이트) |
| **관측 스택 (인프라/프로세스)** | [observability.md](observability.md) | ✅ (도메인 아님 — CloudWatch+Sentry+Amplitude 결정·왜 ES 아닌가) |
| **시간 처리 (크로스도메인 규약)** | [time-handling.md](time-handling.md) | 🔜 (글로벌화 설계 — instant=OffsetDateTime/UTC·local=LocalDate/Time 유지 · 표시 TZ 전략 · 필드 인벤토리 · 리팩토링 대기) |
| **보안 원칙 (크로스커팅)** | [security.md](security.md) | ✅ (도메인 아님 — 신원=세션·객체단위 인가 anti-IDOR·비순차 식별자) |
| **테스트 아키텍처 (크로스커팅)** | [testing.md](testing.md) | ✅ (도메인 아님 — hermetic 원칙·외부 경계 격리 A:stub핀/B:@MockBean·env 누출·새 외부서비스 체크리스트) |

> 🗑️ 표시된 4개 문서(lecture · schedule · reservation · review)는 **v1 레거시 청산(2026-08-15)** 으로 코드가 사라진 도메인이다. 삭제하지 않고 남긴 이유는 "왜 그렇게 만들었고 어떤 간극 때문에 재설계했는지"의 기록이기 때문 — 각 문서 상단에 삭제 배너와 후신 링크가 있다. **현재 동작의 근거로 인용하지 말 것.**

> 검색은 별도 도메인이 아니다 — Phase 3 에서 Elasticsearch 를 제거하고 **MySQL `JpaSpecification`**(제목·강사명 LIKE) 으로 흡수했다. 당시 대상이던 lecture 도메인은 v1 청산으로 사라졌고, 지금은 course 도메인의 `CourseSpecifications` 가 그 역할을 한다. 결정 근거는 [observability.md](observability.md) "왜 Elasticsearch 가 아닌가".

## 갱신 규칙

PR 이 해당 도메인의 컴포넌트 / 흐름 / 모델 / 권한 매트릭스 중 하나라도 바꾸면 같은 PR 안에서 도메인 문서 갱신. 내부 리팩토링이나 테스트만 바꾸는 PR 은 갱신 불필요.

자세한 컨벤션은 루트 [CLAUDE.md](../../CLAUDE.md) 의 "Architectural changes update README" 섹션 참고.
