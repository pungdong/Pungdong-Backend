# 푸시 알림 (push)

> **피처 문서** — 푸시의 **정책·계약(SoT)·결정 히스토리**를 소유한다. **메커니즘(outbox·재시도·상태기계·예외분류)은 복붙하지 않고** [docs/architecture/notification.md](../architecture/notification.md) 로 링크한다 (drift 방지).
> 컨슈머: mobile(RN) + 향후 web. FE 핸드오프(PungDong repo `docs/features/push.md`)는 **클라이언트 멘탈 모델**이고, **계약의 권위는 이 문서**다 — FE 가 여기에 맞춘다.

## 한 줄

도메인 이벤트가 나면 BE 가 [outbox 파이프라인](../architecture/notification.md)으로 **FCM** 을 통해 단말에 푸시한다. 앱의 책임은 **토큰 발급 + 등록 + 탭 라우팅**뿐이고, **발송 판단·전송·신뢰성은 전부 BE 소유**다.

## 협력 도메인 / 구성요소

| 영역 | 구현/문서 | 역할 |
|---|---|---|
| 발송 파이프라인 | [notification.md](../architecture/notification.md) | event → outbox(PENDING) → 워커 재시도 → FCM. at-least-once. |
| 디바이스 토큰 | `account/FirebaseToken` (+ `FirebaseTokenService`) | 토큰 ↔ 유저 1:N upsert, 무효 토큰 정리 |
| FCM 어댑터 | `notification/fcm/FcmGateway` (`FirebaseFcmGateway` / `LoggingFcmGateway`) | `firebase.enabled` 프로퍼티 키잉 ([memory `feedback_conditional_bean_wiring`](#관련-메모리)) |
| 자격증명 | `global/config/FirebaseConfig` | ADC 우선 / `firebase.credentials.path` JSON fallback |
| GCP 프로젝트 | `plop-5997b` (기업 계정, SA `firebase-adminsdk-fbsvc@plop-5997b`) | v2 전용. 옛 `pungdong-21df5` 는 legacy([memory `fcm_two_projects`](#관련-메모리)) |
| 앱 (RN) | PungDong repo | 권한·`getToken`·등록/해제·라우팅 |
| Apple/Android 선결 | PungDong `docs/app-release.md` | APNs `.p8`, push capability, `POST_NOTIFICATIONS` |

## 계약 (SoT) — FE/앱이 맞추는 표면

> 🟡 아래 **목표 계약**은 결정됐고 **아직 미구현**(현재 코드 상태는 각 항목에 명시). 푸시 v2 작업 PR 에서 구현.

### 1. 디바이스 토큰 등록 — `/me/devices` (벤더 비종속)

```
POST   /me/devices            Authorization: Bearer <atk>
  { "token": "<fcm token>", "platform": "IOS" | "ANDROID" }
  → 200/201. token UNIQUE upsert (재등록 = 갱신). 신분은 세션(@CurrentUser), 바디 아님.

DELETE /me/devices/{token}    Authorization: Bearer <atk>
  → 로그아웃 / 회원탈퇴 시 해제. (account-deletion 흐름과 연동: account-deletion.md)
```

- **현재 코드**: `POST /sign/firebase-token` (body `{token}` 만, `deviceType=null` 하드코딩), **DELETE 없음**(`FirebaseTokenService.unregister` 는 존재하나 컨트롤러 미연결).
- **왜 리네임**: 현재 경로는 `/sign`(auth 흐름 네임스페이스) 아래 + `firebase`(벤더명)을 URL 에 박음 — 디바이스 등록은 "sign" 작업도 아니고, 공급자(웹푸시 추가/교체) 바뀌면 경로가 거짓말이 된다. 리소스는 *내 디바이스들*(`/me/devices`)이어야 함.
- **platform 캡처**: 엔티티에 `deviceType` 칸이 이미 있으나 현재 endpoint 가 `null` 로 버림 → 같이 메움. (`model` 은 안 받음 — 엔티티에 없고 가치 낮음.)

### 2. 발송 메시지 `data` envelope — BE 가 정의, 앱은 따름

```jsonc
{ "notification": { "title": "...", "body": "..." },
  "data": {
    "notificationId": "<stable id>",   // 🟡 신규 — at-least-once dedup 키 (아래 §정책)
    "type": "RESERVATION_CREATED",     // 앱이 라우팅 분기
    "lectureId": "123", "scheduleId": "456"
  } }
```

- **현재 코드**: `data = { type, lectureId, scheduleId }` (notification.md §이벤트 타입 매트릭스). `notificationId` **미존재** — 추가 대상.
- 앱은 `data.type` 으로 deep-link 화면을 고르고 `notificationId` 로 dedup 한다. **앱이 키를 임의로 요구하는 게 아니라 BE 스키마를 따른다.**

### 3. 자격증명 — WIF 키리스 (JSON 키 금지)

- prod: AWS ECS task role ↔ `plop-5997b` SA via **Workload Identity Federation**. `FirebaseConfig` 의 ADC 경로 그대로, **코드 변경 0**.
- **FE/사용자가 BE 에 넘길 "키"는 없다.** SA 가 콘솔에 존재하면 됨(존재함). → FE 핸드오프의 "service account JSON 받아 전달" 항목은 **폐기**.
- (로컬/staging 빠른 실송신 검증용으로만 임시 JSON 허용 가능. prod 타겟은 WIF.)

## 정책 / 설계 기조 (왜)

- **BE 가 계약을 리드하고 FE 가 맞춘다.** 어려운 절반(전송 신뢰성)을 이미 푼 쪽이 계약을 정한다. FE 핸드오프는 토큰등록·탭라우팅까지만 정확하고 **전송 신뢰성은 보이지도 않는다**(fire-and-forget 가정). API 계약 권위 = BE([root CLAUDE.md](../../CLAUDE.md) "TypeScript API contract", types.ts SoT).
- **미전송 건을 잃지 않는다 (durability).** [outbox](../architecture/notification.md) 로 "전송 *시도*"는 at-least-once 로 보존(`GAVE_UP` 도 영구). 단 **마지막 구간 FCM→단말은 보장 불가**(권한 off·토큰 죽음·OS 드랍) → 진짜 종착지는 **인앱 알림함**(서버 권위 레코드), 푸시는 그 위 best-effort 넛지. → [#132](https://github.com/pungdong/Pungdong-Backend/issues/132).
- **at-least-once ⇒ 중복 전송 불가피 ⇒ dedup id 를 *지금* 박는다.** 부분 성공·markSent 실패 후 재시도로 같은 푸시가 두 번 갈 수 있다. `data.notificationId` 로 앱이 dedup. **나중에 추가하면 앱 재배포까지 엮인 FE 재협상**이 생기므로 1차 스코프에 포함. 이 id 는 후속 알림함(#132) 행과도 매칭.
- **신분은 세션, 벤더는 URL 밖.** 토큰 등록도 `@CurrentUser` 기준([security](../architecture/security.md)), 경로는 벤더 비종속.

## FE 핸드오프와의 차이 — 정정점

FE 핸드오프(PungDong `docs/features/push.md`)는 제안이고, 아래 3점은 **이 문서 기준으로 정정**한다. (FE 가 또 다른 계약을 제안해 혼란 만드는 것 방지가 이 섹션의 목적.)

| FE 핸드오프 | 이 문서 기준 (확정) |
|---|---|
| "service account JSON 받아 BE 에 전달" | **삭제.** BE 는 WIF 키리스, 넘길 키 없음(SA 존재로 충분). |
| `POST/DELETE /me/devices` (제안) | **확정** — 동일 결론(벤더 비종속). 바디 `{token, platform}`. |
| `data { type, targetId }` | `data` 키는 BE 정의: `notificationId`(신규) + `type` + 도메인 id(`lectureId`/`scheduleId`). |
| "BE repo `docs/architecture/push.md` 미러" | 슬러그는 같되 **이 파일(`docs/features/push.md`)** 이 BE SoT(교차·정책 문서라 features). FE 링크는 여기로. |
| (없음) APNs `.p8` 선결 | **유지** — 정확. iOS 무음실패 방지. FE/Apple 계정 일, BE 무관. |

## 결정 히스토리

| 시점 | 결정 | 근거 / PR |
|---|---|---|
| 2026-04-29 | FCM 자격증명 = WIF 키리스, JSON 키 노출 없음 | Phase 4 정적키-0 기조 ([memory `operations_decisions`](#관련-메모리)) |
| PR #11 | `FirebaseConfig` ADC + 파일 자격증명 두 경로 | WIF 대비 |
| PR #97 | `FcmGateway` 를 `firebase.enabled` 프로퍼티 키잉 | prod 부팅 회귀 ([memory `feedback_conditional_bean_wiring`](#관련-메모리)) |
| 2026-06-29 | FE 푸시 v2 핸드오프 수신 → **BE 리드/FE 컨폼**으로 정리 | 핸드오프는 멘탈 모델, 신뢰성 미포함 |
| 2026-06-29 | 계약 = `/me/devices`(+platform), `data.notificationId` dedup, 자격증명 WIF 재확인 | 이 문서 |
| 2026-06-29 | 인앱 알림함을 durability 종착지로 결정, 출시 후 | [#132](https://github.com/pungdong/Pungdong-Backend/issues/132) |

## 미해결 / 확장

- 🔴 **WIF 신뢰 설정 (AWS ECS task role → `plop-5997b` SA)** — 인프라(Terraform). 미완([memory `operations_decisions`](#관련-메모리) 의 TODO). **이게 비면 `firebase.enabled=true` 여도 ADC 가 신분을 못 얻어 실송신 안 됨** = 푸시 실송신의 마지막 한 칸.
- 🔴 **BE 코드 (푸시 v2 PR)** — `/sign/firebase-token` → `/me/devices` 리네임 + `DELETE` + `platform` 캡처 + `data.notificationId` + types.ts 갱신 + 마이그레이션(필요 시).
- 🟡 **앱 v2** — 권한·`getToken`·등록/해제·`onTokenRefresh`·foreground/background 핸들러·탭 라우팅 (FE).
- 🟡 **인앱 알림함 (durable feed)** — [#132](https://github.com/pungdong/Pungdong-Backend/issues/132). 출시 후. dedup id 가 선결로 깔림.
- 🟢 **이벤트 카탈로그 확장** — 현재 예약생성/취소·강의공지 3종. 수강신청 수락·채팅 등은 해당 도메인 작업 시 ([notification.md §확장 자리](../architecture/notification.md)).
- 🟢 **만료 토큰 정리** — 현재 무효 토큰은 발송 시 reactive 삭제만. last-seen 기반 정리는 검토(notification.md).

## 관련 메모리

- [`fcm_two_projects`](#) — 옛 `pungdong-21df5` / 새 `plop-5997b` 두 프로젝트 혼동 주의, BE 는 어느 쪽에도 실연결 없음(늘 stub)
- [`operations_decisions`](#) — FCM = WIF 키리스 (2026-04-29), WIF 설정은 TODO
- [`phase_4_deployment_decisions`](#) — AWS↔GCP OIDC/WIF, 정적키 0
- [`feedback_conditional_bean_wiring`](#) — `firebase.enabled` 프로퍼티 키잉 이유(#97)
