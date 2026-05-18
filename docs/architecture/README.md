# 도메인별 아키텍처 문서

전체 시스템 그림은 [루트 README](../../README.md#아키텍처-현재-상태) 의 Mermaid 다이어그램. 이 디렉토리는 그 안의 **개별 도메인** 을 한 단계 줌인해서 본다.

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
| 강의 (lecture) | _TODO_ | 🔜 |
| 일정 (schedule) | [schedule.md](schedule.md) | ✅ (baseline — 동시성 / 시간 충돌 / 수정 API 부재) |
| 예약 (reservation) | _TODO_ | 🔜 |
| 후기 (review) | [review.md](review.md) | ✅ (baseline — 통계 갱신 버그) |
| 검색 (search / Elasticsearch) | _TODO — Phase 3 정리 후_ | ⏸️ |

## 갱신 규칙

PR 이 해당 도메인의 컴포넌트 / 흐름 / 모델 / 권한 매트릭스 중 하나라도 바꾸면 같은 PR 안에서 도메인 문서 갱신. 내부 리팩토링이나 테스트만 바꾸는 PR 은 갱신 불필요.

자세한 컨벤션은 루트 [CLAUDE.md](../../CLAUDE.md) 의 "Architectural changes update README" 섹션 참고.
