# API Clients (TypeScript)

이 디렉토리는 모바일 / 웹 (TypeScript) 클라이언트가 백엔드 API 를 호출할 때 쓰는 **타입 계약** 의 단일 출처다.

## 어디에 뭐가 있나

| 파일 | 역할 |
|---|---|
| [`types.ts`](types.ts) | 모든 request / response / 공통 envelope 타입 선언. **FE Claude 가 처음 읽는 파일.** |
| `../architecture/<domain>.md` | 사람이 도메인을 이해하는 그림 / 흐름. Claude 가 동작 의미를 파악할 때 참고. |

REST Docs HTML 도 빌드 산출물로 생성된다 (`./gradlew build` → `static/docs/api.html`, 운영 시 `https://api.pungdong.com/docs/api.html` 로 노출 예정). 사람용 reference 가 필요할 때 보면 되고, **TypeScript 타입 생성 자체는 `types.ts` 만 읽으면 된다.**

## FE Claude 가 작업하는 법

> 컨텍스트 프롬프트 템플릿:
>
> "백엔드 API 의 TypeScript 타입은 `https://raw.githubusercontent.com/pungdong/Pungdong-Backend/master/docs/api-clients/types.ts` 가 단일 출처다. 이 파일의 export 를 그대로 사용하거나, 필요하면 프로젝트로 복사해 두고 import 해라. 새 API 가 필요해서 타입이 없으면 BE 측에 추가 요청을 남길 것."

복사해서 쓸 경우 권장 위치: FE 프로젝트의 `src/api/types.ts`. BE 측 변경 시 git pull 로 동기화하거나, 빌드 단계에서 raw URL 을 fetch 해서 자동 덮어쓰기 (`curl ... > src/api/types.ts`).

## BE Claude 가 지켜야 할 규칙

API 컨트롤러의 request/response 시그니처가 바뀌는 PR 은 **같은 PR 안에서 `types.ts` 를 업데이트**한다. 도메인 아키텍처 문서와 동일한 per-PR 갱신 원칙. CLAUDE.md 의 "TypeScript API contract" 섹션 참고.

체크리스트:

- [ ] 새 엔드포인트 추가 → `types.ts` 에 request + response interface 추가
- [ ] 응답 필드 추가 / 변경 / 제거 → 해당 interface 갱신
- [ ] 새 enum / 도메인 값 → `types.ts` 의 enum 추가 또는 union literal 갱신
- [ ] 공통 envelope (`CommonResult`, `SingleResult<T>`, `ListResult<T>`) 변경 → 최우선 갱신

## 향후 자동화 (검토 중)

`springdoc-openapi` 추가 → `/v3/api-docs` 가 OpenAPI JSON 자동 emit → FE 에서 `openapi-typescript` 로 변환 자동 생성. 솔로 dev + 출시 임박 (~2026-06-12) 상황에 새 의존성 / build tooling 도입은 risk 라서 현재는 수동 `types.ts` 유지. 출시 이후 코드 변경량이 많아지면 검토.
