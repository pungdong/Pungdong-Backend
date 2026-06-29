# 이미지 변환 Lambda (`/r/*`)

CloudFront 의 `/r/*` behavior origin. 온디맨드 리사이즈/포맷 변환(sharp). 원본은 `/{key}`(S3 직접)
그대로고, 리사이즈만 이 Lambda 가 처리한다 — **변환이 깨져도 원본/웹은 영향 없음(fail-safe)**.
설계·결정 근거: [`docs/features/image-storage-and-serving.md`](../../docs/features/image-storage-and-serving.md) §4.

## URL 계약

```
원본:    https://cdn.plop.cool/course/<uuid>.jpg
리사이즈: https://cdn.plop.cool/r/course/<uuid>.jpg?w=400&fm=webp&q=80&fit=inside
```

| 쿼리 | 의미 | 기본 |
|---|---|---|
| `w` / `h` | 목표 px (1..4000). 하나만 줘도 비율 유지 | — |
| `fm` | `webp`\|`avif`\|`jpeg`\|`png` | 원본 포맷 |
| `q` | 품질 1..100 | 80 |
| `fit` | `cover`\|`contain`\|`inside`\|`outside`\|`fill` | `inside`(확대 안 함) |

파라미터가 전혀 없으면 원본 통과. 결과는 CloudFront 가 엣지 캐시 → Lambda 는 캐시 미스에만 실행.

## 빌드 (배포 전 필수)

```bash
cd infra/image-lambda && ./build.sh
```
`sharp` 는 네이티브 모듈이라 **Lambda 런타임(arm64 glibc)용 바이너리**가 필요하다. `build.sh` 가
`--os=linux --cpu=arm64` 로 받아 `package/` 에 모은다(로컬이 mac 이어도 OK). Terraform 의
`archive_file` 이 `package/` 를 zip 해 배포한다.

## 배포 / 검증 (hands-on — 자동 테스트 불가)

> sharp 네이티브 빌드 + Lambda 실행은 CI 단위테스트로 못 잡는다. 아래는 사람이 한 번 돌려 확인.

1. `./build.sh`
2. `cd ../envs/dns && terraform plan` 검토 → `apply` (변환 Lambda·Function URL·OAC·`/r/*` behavior 생성).
3. 확인: `curl -o out.webp "https://cdn-staging.plop.cool/r/course/<업로드한키>?w=400&fm=webp"` → 200·작은 webp.
   원본도 여전히 OK: `curl -I "https://cdn-staging.plop.cool/course/<키>"`.
4. 앱: 썸네일은 `/r/{key}?w=&fm=webp`, 상세는 더 큰 `w`. 웹(next/image)은 원본 URL 그대로 사용.

## 인증 (OAC, AWS_IAM)

Function URL = `AuthType=AWS_IAM` + CloudFront OAC(SigV4)로만 호출 → **공개 도달 없음**(직접 호출은
미서명이라 403). 권한은 `cloudfront.amazonaws.com` 주체에 SourceArn=배포 조건으로
`lambda:InvokeFunctionUrl` + `lambda:InvokeFunction` **둘 다** 부여(cdn-transform.tf).

- ★ **함정**: NONE 이든 OAC 든, Function URL 호출이 동작하려면 호출 주체에 **InvokeFunctionUrl(URL 도달)
  + InvokeFunction(함수 실행) 둘 다** 필요. 콘솔은 자동 추가하지만 **Terraform/CLI 는 명시해야** 함.
  InvokeFunctionUrl 만 줘서 OAC·NONE 둘 다 403 났던 게 디버깅의 핵심이었다.
- Lambda@Edge(C)는 OAC 로 정석(공개 도달 없음) 달성해 불필요.
- 전체 결정·근거: [`docs/features/image-storage-and-serving.md`](../../docs/features/image-storage-and-serving.md) §4.

## 운영 메모

- Lambda: nodejs20.x, **arm64**(Graviton, 저렴), memory 1536MB(=CPU 충분→변환 빠름), timeout 30s.
  (reserved concurrency 는 계정 unreserved 최소 10 제약으로 미설정.)
- 콜드스타트는 "캐시 미스 + 콜드"인 드문 경우만. 결과 캐시되니 1회성.
- 새 포맷/사이즈 추가 = 코드 수정 없이 쿼리만. sharp 업데이트는 `build.sh` 재실행 후 apply.
