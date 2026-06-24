# Sanity 읽기 캐싱 · freshness 정책

> **교차 도메인 정책 문서** — BE 가 Sanity 를 *서버사이드로* 읽을 때의 캐시·갱신 규칙을 소유한다. 도메인별 구현은 링크만(venue 는 [docs/features/venue.md](../features/venue.md)). FE-direct(CDN) freshness 는 Sanity 가 알아서(아래 "두 경로") — 이 문서는 **BE-side** 다.

## 한 줄

거의 안 바뀌는 Sanity 콘텐츠는 **긴 TTL/reconcile 바닥 + publish webhook 으로 "변경된 문서만" 즉시 무효화**. 웹훅은 **정합성의 조건이 아니라 지연 최적화** — 유실·차단돼도 바닥(TTL/reconcile)이 따라잡는다.

## 두 경로 (혼동 방지)

| 경로 | 누가 신선도 책임 | freshness |
|---|---|---|
| **FE-direct (CDN)** — 공개 표시(cert org·term·venue official) | **Sanity** | publish 시 CDN flush(purge-on-publish). 우리 일 0. (sanity/CLAUDE.md "읽기 기조") |
| **BE-side (이 문서)** — 통합/검증 때문에 BE 가 읽어 캐시 | **우리** | 아래 정책 |

## 정책 (BE-side)

1. **바닥(correctness) = TTL 또는 read-side `_rev` reconcile.** 웹훅과 무관하게 **최종 일관성을 보장하는 안전망**. 이게 있어야 웹훅을 "best-effort 최적화"로 둘 수 있다.
2. **웹훅(latency) = publish 시 변경 문서만 무효화/refetch.** HMAC 검증 + delivery dedup + **항상 즉시 2xx** + 실패해도 바닥이 회복(best-effort). venue `SanityWebhookController` 패턴 그대로 일반화.
3. **무효화 단위 = 변경된 `_id`/`_type` 만.** 전체 flush 아님(= on-demand ISR). siteSettings 처럼 싱글톤이면 그 한 값만.

## 항목별 가이드 (바닥 길이는 변경빈도 × staleness 비용)

| 항목 | 성격 | 바닥(floor) | 웹훅 | 비고 |
|---|---|---|---|---|
| cert organization · term | 정적 카탈로그, 거의 불변 | **긴 TTL/reconcile (시간~일, 예: 24h)** | 선택 | term 은 현재 동의 시점 fetch(캐시 없음)라도 무방(빈도 낮음) |
| venue official (공식 위치) | 정적, 가끔 수정 | `_rev` reconcile 주기(분 단위) | ✅ 구현됨 | 설계 = [docs/features/venue.md](../features/venue.md) "캐싱·동기화·모니터링" |
| **siteSettings (런칭 토글)** | 드물게 바뀌나 **즉시성 필요** | **짧은 백스톱(분 단위)** | ✅(목표) | launch-critical — 웹훅 유실 시 24h 갇히면 위험하므로 바닥은 짧게 |

> **요점**: TTL 24h 는 *진짜 정적*(cert/term)엔 적합하지만, **siteSettings 엔 부적합** — 웹훅이 즉시성을 담당하더라도 *웹훅 유실 시 바닥까지 stale* 이므로, launch flag 의 바닥은 분 단위로 짧게 둔다(또는 수동 flush). "한 숫자로 전부 통일"보다 **항목별 바닥**이 맞다.

## ⚠️ 로컬 dev — 웹훅 수신 불가

Sanity 는 `localhost` 로 웹훅을 못 쏜다(공개 HTTPS 필요). 그래서 dev/prod **정책은 동일**(TTL 바닥 + 웹훅)하되, **로컬만 웹훅 transport 가 없어** 갱신 방법이 다르다 — *동작 규칙이 갈리는 게 아니라 전송수단이 없는 것*:

- **로컬 기본 = 앱 재시작**(인메모리 캐시 초기화) 또는 **수동 flush**(있다면).
- 즉시성이 필요하면 **ngrok 등 터널**로 로컬도 웹훅 수신 가능(선택).
- 그래서 "dev TTL 길게 + 웹훅 통일"은 **배포 환경(staging/prod)에서 성립**하고, 로컬은 재시작으로 메운다. (재시작은 dev 에서 흔하므로 실무상 불편 작음.)

## 웹훅 보안 · idempotency (venue 구현 기준)

- **인증 = HMAC**(`sanity-webhook-signature` 헤더, `SanityWebhookVerifier`). JWT 아님 → 매처는 `permitAll` + HMAC 으로 인증. 검증 실패만 `401`.
- **dedup**: Redis 에 delivery id 기록(재전송 1회만 처리). reconcile 자체도 idempotent.
- **항상 2xx ack**: 처리 실패해도 200 → 바닥이 회복(웹훅은 최적화일 뿐).
- GROQ projection 으로 **해당 `_type` 일 때만** 쏘게 Sanity 측에서 필터.

## 현재 상태 vs 타겟

| | 현재 | 타겟 |
|---|---|---|
| siteSettings | 인메모리 TTL **60초**, 웹훅 없음 | 분 단위 백스톱 + `POST /webhooks/sanity/site-settings`(HMAC) |
| venue official | `OfficialVenueCache` + `_rev` reconcile + `/webhooks/sanity/venue`(HMAC) | (구현됨, 유지) |
| term | 동의 시점 fetch(캐시 없음) | 유지(빈도 낮음) |

**통일 작업**(긴 TTL 기본화 + siteSettings 웹훅)은 **staging(공개 HTTPS)이 생길 때 같이** — 웹훅 엔드포인트가 공개로 reachable 해야 의미 있으므로 Phase 4 와 묶는다. 그 전까지 siteSettings 는 60초 TTL 유지(로컬·미배포에서 토글 확인 가능).

## 결정 히스토리

- **2026-06-24 dev/prod 캐싱 통일안 채택** — "긴 TTL 바닥 + publish 웹훅 on-demand 무효화(변경 문서만)". 사용자 제안. venue 웹훅 패턴(HMAC+dedup+best-effort)을 전 Sanity 읽기로 일반화. 단 ① **로컬은 웹훅 불가**라 재시작/flush 예외, ② **siteSettings 는 launch-critical** 이라 바닥을 분 단위로(24h 통일 X) — 두 보정 포함.

## 관련

- [sanity/CLAUDE.md](../../sanity/CLAUDE.md) "읽기 기조 / freshness" — FE-direct(CDN) 쪽 + 이 문서 포인터
- [docs/features/venue.md](../features/venue.md) — venue official reconcile·모니터링 상세
- [docs/features/launch-and-seeded-content.md](../features/launch-and-seeded-content.md) — siteSettings 가 무엇을 토글하나
