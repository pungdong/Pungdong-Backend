# CLAUDE.md — API Clients

이 디렉토리는 모바일 + 웹 (TypeScript) 클라이언트의 API 계약 단일 출처. 작업 디렉토리가 이 폴더면 이 파일이 자동 로드. 전체 프로젝트 컨벤션은 루트 [CLAUDE.md](../../CLAUDE.md) 참고.

> "**언제** 가 아니라 **어떻게**" — types.ts 를 *언제* 갱신해야 하는지는 루트 CLAUDE.md 의 "TypeScript API contract". 이 파일은 *실제 편집할 때 어떻게 쓸지* 의 가이드.

---

## 파일 구조

| 파일 | 역할 |
|---|---|
| [`README.md`](README.md) | FE Claude 가 처음 읽는 entry. raw URL 가이드 / 갱신 정책 / 자동화 검토 메모 |
| [`types.ts`](types.ts) | 실제 TypeScript 타입 (envelope / enum / request·response interface) |

## 단일 출처 원칙

FE 측은 다음 URL 을 raw 로 fetch 해서 자기 프로젝트로 카피한다:

```
https://raw.githubusercontent.com/pungdong/Pungdong-Backend/master/docs/api-clients/types.ts
```

BE 가 컨트롤러 시그니처 / DTO 를 바꾸면 **같은 PR 안에서** `types.ts` 도 같이 갱신. 도메인 문서 갱신 규칙과 동일 원칙.

---

## types.ts 갱신 체크리스트

PR 에서 컨트롤러를 건드릴 때마다:

- [ ] 새 엔드포인트 → request + response interface 추가
- [ ] 응답 필드 추가 / 변경 / 제거 → 해당 interface 갱신
- [ ] 새 enum / 도메인 값 (`Role`, `AuthProvider`, `Gender`, `DeviceType` 등) → enum literal union 갱신
- [ ] 공통 envelope (`CommonResult`, `SingleResult<T>`, `ListResult<T>`, `AuthToken`, `HalLinks`) 변경 → 최우선 갱신
- [ ] 새 에러 code → `ErrorCode` const 객체에 추가

---

## 작성 규약

### Enum 은 union literal

BE 의 Java enum 명을 대문자 그대로 옮긴다:

```typescript
export type Role = 'STUDENT' | 'INSTRUCTOR' | 'ADMIN';
export type AuthProvider = 'EMAIL' | 'KAKAO' | 'NAVER' | 'APPLE';
```

이유: TypeScript `enum` 보다 union literal 이 tree-shake / 자동완성 / JSON 직렬화 모두 자연스럽고, BE 의 `@Enumerated(EnumType.STRING)` 와 정확히 1:1 매핑.

### HAL 응답은 `extends HalLinks`

HATEOAS 응답 (`Content-Type: application/hal+json`) 은 본 페이로드 위에 `_links` 가 추가됨. interface 에 옵셔널로 표현:

```typescript
export interface SignUpResponse extends HalLinks {
  email: string;
  nickName: string;
  tokens: AuthToken;
}
```

또는 type alias 의 경우 `&` 사용:

```typescript
export type LoginResponse = AuthToken & HalLinks;
```

### Request 와 Response 분리

같은 type alias 가 양방향으로 쓰이지 않게. POST 요청 본문은 `*Request`, 응답은 `*Response`.

### 섹션 주석으로 도메인 그룹화

도메인 단위로 큰 헤더 주석을 두고, 그 안에 관련 type 들을 모아둠. 예:

```typescript
// ============================================================
// 회원가입 + 로그인 (sign-up 도메인)
// docs/architecture/sign-up.md 참고
// ============================================================

export interface SignUpRequest { ... }
export interface SignUpResponse extends HalLinks { ... }
// ...
```

크로스 도메인 의존이 있으면 (예: `AuthToken` 이 여러 도메인에서 쓰임) 별도 envelope / auth 섹션에 배치.

### 코멘트는 Java 의 의도가 코드만으로 안 보일 때만

- ✅ 좋은 코멘트: "BE 는 `Bearer ` prefix 없이 raw JWT 받음 — `Authorization: <token>`"
- ❌ 불필요한 코멘트: "// 회원가입 응답 타입" (이미 이름이 SignUpResponse)

---

## FE Claude 가 들고가는 프롬프트

새 FE Claude 세션 시작 시 다음을 첫 메시지로 던지면 됨 (URL + README.md 첫 단락 + 인증 흐름 요약):

```
백엔드 API 타입의 단일 출처:
https://raw.githubusercontent.com/pungdong/Pungdong-Backend/master/docs/api-clients/types.ts

자세한 사용법: https://github.com/pungdong/Pungdong-Backend/blob/master/docs/api-clients/README.md

핵심 동작:
- 회원가입 응답에 tokens 동봉 (auto-login, 별도 /sign/login 불필요)
- 401 → /sign/refresh { refreshToken } 으로 자동 갱신 (refresh 도 회전됨)
- Authorization 헤더는 raw JWT (Bearer prefix 없음)
- 4xx/5xx 응답은 { success: false, code: number, msg: string } 형태
```

본문 전체는 `README.md` 에 있으므로 FE Claude 가 그 링크만 따라가도 됨.

---

## 자동화 (검토 중)

`springdoc-openapi` 도입 시:

1. `build.gradle` 에 `org.springdoc:springdoc-openapi-ui` 추가
2. `/v3/api-docs` 가 자동 생성 (REST Docs 와 무관, 별도 엔드포인트)
3. FE 측에서 `openapi-typescript` 또는 `@hey-api/openapi-ts` 로 변환

검토 시점: 출시 후 API 변경 빈도가 높아 수동 유지 비용 > 자동화 도입 비용일 때. 현재는 솔로 + 출시 임박 + API 안정화 진행 중이라 **수동 유지가 더 가벼움**. 결정 시 [README.md](README.md) 의 "향후 자동화" 섹션 갱신.
