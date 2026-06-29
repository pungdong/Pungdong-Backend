# 이미지 저장·서빙 (image storage & serving)

> 여러 도메인(강사신청·코스·프로필·리뷰)에 걸친 **교차 피처**. 이 문서는 *정책·왜·결정 히스토리*를 소유한다. 구현(어댑터·엔드포인트)은 각 도메인 문서로 링크한다.
> 관련 메모리: `s3_image_access_classes`.

## 한 줄 요약

이미지는 **접근 등급(access class)** 으로 두 갈래다 — **비공개(PII)** 는 비공개 버킷 + presigned, **공개(노출용)** 는 CloudFront(OAC) + 커스텀 도메인. **한 버킷에 섞지 않는다.** 등급이 정반대의 요구(은닉 vs 영구공개·SEO)를 갖기 때문.

---

## 1. 두 접근 등급과 그 근거

| 등급 | 대상 | 요구사항 | 저장/서빙 | 버킷 |
|---|---|---|---|---|
| **비공개 (PII)** | 자격증·보험 | 어드민·본인만, 은닉 | 객체 key 저장, 조회 시 **presigned GET(TTL 3분)** | `plop-{env}-uploads` (BPA 4종 ON) |
| **공개 (노출)** | 코스·프로필·리뷰 (+ 향후 커뮤니티) | 영구·안정 공개 URL, **SEO**, 빠른 로딩 | 완성된 **CDN URL** 저장·반환 | `plop-{env}-public` (BPA 유지·비공개, **CloudFront OAC로만 노출**) |

**왜 한 버킷에 안 섞나** — 버킷 단위로 공개정책/BPA가 갈리고, 같은 버킷에 PII와 공개물을 섞으면 prefix 정책 오설정 한 번에 개인정보가 노출된다. 버킷으로 물리 분리하는 게 사고 표면을 없앤다.

---

## 2. 비공개(PII) — presigned 선택 근거 (PR #138, 2026-06-29)

자격증/보험 이미지는 개인정보. 공개 URL로 두면 안 된다.

- **버킷 비공개 + 객체 key 저장**(`instructorCertificate/{accountId}/{uuid}`). 회원별 그룹핑 → 탈퇴 PII 익명화 시 prefix 일괄 삭제. 키에 PII(이메일) 없음.
- **조회 시점에만 presigned GET(TTL 3분) 발급** — 어드민/본인 응답에서.
- **왜 presigned (대안: 공개 URL / 인증 프록시):**
  - 비공개 버킷이라 키를 알아도 **SigV4 서명 없이는 403** — 추측·열거로 못 연다. 위조는 암호학적으로 불가.
  - 유출 위험은 *발급된 URL 문자열*이 로그/Referer/공유로 새는 것뿐 → **짧은 TTL이 그 창을 닫는다.** 저빈도(심사 1회) PII 열람엔 업계 표준.
  - **인증 프록시**(매 요청 세션검증 후 바이트 스트림)는 더 강하지만(베어러 URL 없음·매요청 인가·즉시 철회) BE 대역폭/엔드포인트 비용. 출시 시점 강사 온보딩엔 과함. 단 `CertificateImageStorage.viewUrl` 구현만 바꾸면 **계약 변경 없이** 프록시로 하드닝 가능(여지 남김).
- 구현: [docs/architecture/instructor-application.md](../architecture/instructor-application.md).

---

## 3. 공개(노출) — CloudFront + OAC + 커스텀 도메인 선택 근거

코스/프로필/리뷰 이미지는 노출이 목적. SSG로 정적 페이지에 URL을 박고(hardlink) **SEO를 차별점**으로 가져간다.

### 왜 presigned가 아니라 영구 공개 URL인가
presigned는 **만료 + 쿼리 서명**이라 크롤러·SSG가 인덱싱/하드링크할 수 없다. 공개물엔 구조적으로 부적합. → **안정·영구 공개 URL** 필요.

### 왜 CloudFront(OAC) — 공개 버킷 직접이 아니라
1. **SEO = 성능(Core Web Vitals/LCP).** 단일 리전(ap-northeast-2) S3 직접은 원거리 사용자에게 느리고 엣지 캐시가 없다. CloudFront 엣지 캐싱 → LCP 개선 → 랭킹 신호. (크롤링 가능성 자체는 S3 URL도 되지만, **속도/브랜드**가 CDN의 이득.)
2. **보안 — 버킷을 공개로 안 풀어도 됨.** **OAC(Origin Access Control)** 로 CloudFront만 SigV4로 origin을 읽고, 외부는 CDN 도메인으로만 접근. **버킷 BPA 4종 유지(완전 비공개).** "공개 이미지인데 S3 버킷 자체는 비공개" → public-read 버킷 정책 방식보다 안전.
3. **안정 URL의 스토리지 분리** — 뒤의 origin을 바꿔도 CDN URL 불변. SSG hardlink에 안전.

### 왜 커스텀 도메인을 day-1에 (`cdn.plop.cool` / `cdn-staging.plop.cool`)
- 브랜드 URL + 처음부터 도메인 고정 → **저장값을 완성된 CDN URL로** 둘 수 있어 코스/프로필/리뷰 **FE 계약 변경 0**(필드 그대로, 값만 실제 열리는 CDN URL). 도메인이 안 바뀌니 후일 마이그레이션 불필요.
- staging도 커스텀(`cdn-staging`) — **prod와 동일 생태계 테스트**. 추가비용 ≈ Route53 존 $0.5/월(ACM 무료, CloudFront 사용량 과금=dev 트래픽 ≈0)로 무시 가능.
- **us-east-1 ACM** — CloudFront 인증서는 버지니아 필수(나머지가 ap-northeast-2여도). 흔한 함정이라 명시.

### DNS
루트 `plop.cool`은 Squarespace(provider 없음). api 서브도메인처럼 `cdn`·`cdn-staging`만 **Route53로 위임**(Squarespace에 NS 일회성) → 이후 ACM 검증·alias를 Terraform이 자동. (`infra/envs/dns/` 패턴 재사용.)

---

## 4. 클라이언트별 이미지 최적화 — 역할 분담

| 클라이언트 | 최적화 |
|---|---|
| **웹 (Next.js/Vercel)** | `next/image` 가 CDN URL을 소스로 리사이즈/WebP/AVIF. BE는 안정 원본 URL만 제공. `next.config` `images.remotePatterns` 에 `cdn(-staging).plop.cool` 추가 필요. |
| **모바일 앱** | Vercel 같은 최적화 레이어 없음 → **BE/엣지가 최적화 제공 필요.** |

### 엣지 이미지 변환 — 후속 PR로 페이징한 이유
모바일 앱 최적화의 본진은 **CloudFront 위 엣지 변환**(AWS *Dynamic Image Transformation for CloudFront*, 구 Serverless Image Handler): `cdn.plop.cool/{key}?w=400&fm=webp` 식 온디맨드 리사이즈/포맷 + 엣지 캐시. 웹·앱 **동일 origin으로 통일**.

- **왜 지금 안 하나**: 별도 시스템(Lambda@Edge + Terraform + 테스트)이라 CDN 기반 PR과 묶으면 비대. **계약을 안 바꾸고 위에 얹을 수 있다** — 현재 `{cdn}/{key}` 스킴에 쿼리파라미터만 추가되므로. 기반(이 PR)을 먼저 깔고 변환은 후속.
- **stopgap(기각)**: 업로드 시 고정 사이즈 파생 2~3개 생성. 유연성↓(임의 사이즈 불가)·업로드 지연·재처리 부담 → 엣지 변환이 정답.

---

## 5. 결정 히스토리

| 시점 | 결정 | 근거 | PR |
|---|---|---|---|
| 2026-06-29 | **비공개 이미지 = 비공개 버킷 + presigned(TTL 3분)** | 자격증=PII, 공개 불가; 짧은 TTL로 유출창 차단; 프록시로 하드닝 여지 | #138 |
| 2026-06-29 | **공개 이미지 = 별도 public 버킷, CloudFront(OAC) + 커스텀 도메인(prod·staging 둘 다)** | SEO=LCP/속도 + 브랜드 안정 URL; OAC로 버킷은 비공개 유지(보안); day-1 도메인 고정 → FE 계약 무변경 | (이 PR) |
| 2026-06-29 | **엣지 이미지 변환은 후속 PR** | 모바일 앱 최적화 본진이나 별도 시스템; `{cdn}/{key}?w=&fm=` 로 계약 변경 없이 후일 추가 | (후속) |

---

## 6. 미해결 / 로드맵

- 🔴 **엣지 이미지 변환**(모바일 앱 최적화) — CloudFront 위 온디맨드 리사이즈/WebP. 위 §4.
- 🟡 **레거시 `/lecture`·`/lectureImage` 이미지** — Course가 대체 중. 공개 버킷 전환 대상에서 제외(레거시), Course로 수렴 시 정리.
- 🟡 **커뮤니티/SNS 이미지** — 기능 도입 시 공개 버킷에 같은 패턴 적용.
- 🟡 **기존 비공개 버킷의 공개-의도 잔존물** — #138 이후 코스/프로필/리뷰가 잠시 비공개 버킷에 업로드되던 구간(서빙 불가). 이 PR로 공개 버킷 전환. (강사 부재로 staging 실데이터 영향 없었음.)
