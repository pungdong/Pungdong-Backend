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

## 인증 (B 시크릿 헤더, interim)

Function URL = `AuthType=NONE` 이지만, CloudFront 가 origin custom header `x-origin-secret` 에 비밀값을
주입하고 Lambda 가 검증(없으면 403). 즉 **사실상 CloudFront 만** 호출. 비밀값은 Terraform `random_password`
(Lambda env `ORIGIN_SECRET` = CloudFront origin header), 클라에 노출 안 됨.

- **왜 OAC(AWS_IAM) 가 아닌가**: 처음엔 OAC 403 으로 보류했으나, 근본원인은 `lambda:InvokeFunction`
  권한 누락(InvokeFunctionUrl + InvokeFunction 둘 다 필요)이었음 — 그래서 NONE 도 같은 이유로 막혔던 것.
- **장기 정석 = OAC 복귀**: 원인이 권한이라 OAC 도 CloudFront 주체에 InvokeFunction 추가하면 viable →
  공개 엔드포인트 없는 정석에 Lambda@Edge 큰 rework 없이 도달. 여유 시 하드닝. (Lambda@Edge 는 폴백.)
- 전체 결정·근거: [`docs/features/image-storage-and-serving.md`](../../docs/features/image-storage-and-serving.md) §4.

## 운영 메모

- Lambda: nodejs20.x, **arm64**(Graviton, 저렴), memory 1536MB(=CPU 충분→변환 빠름), timeout 30s,
  **reserved concurrency 5**(비용/DoS 캡 — 변환은 캐시 미스에만이라 충분).
- 콜드스타트는 "캐시 미스 + 콜드"인 드문 경우만. 결과 캐시되니 1회성.
- 새 포맷/사이즈 추가 = 코드 수정 없이 쿼리만. sharp 업데이트는 `build.sh` 재실행 후 apply.
