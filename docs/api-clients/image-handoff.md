# 이미지 처리 — FE/앱 핸드오프 (한 번에)

이미지 저장·서빙을 두 등급으로 정리했다. **자격증/보험(비공개)** 과 **코스/프로필/리뷰(공개)** 의 FE 작업이 다르다.
계약의 단일 출처는 [`types.ts`](types.ts). 정책/왜는 [docs/features/image-storage-and-serving.md](../features/image-storage-and-serving.md).

---

## 1. 자격증·보험 이미지 (비공개) — 변경 있음 ⚠️  (BE PR #138 머지됨)

개인정보라 비공개 버킷에 저장하고, 표시는 **한시 presigned URL(3분)** 로만 한다.

**필드 변경**
- 업로드 응답 `POST /instructor-applications/certificate-images`: ~~`fileURL`~~ → **`fileKey`** (공개 URL 아님, 저장 참조 key).
- 제출 `POST /instructor-applications`, 자격증추가 `POST /instructor-applications/certificates`: 자격증 항목에 ~~`fileURL`~~ → **`fileKey`** 전송.
- 조회 응답(내 신청 `GET /me`, 어드민 상세): 자격증 항목에 **`viewUrl`** 신규 — 표시는 이걸로.

**FE 동작 규칙**
1. 업로드 직후 **미리보기는 방금 고른 로컬 파일(blob)** 로. 업로드 응답값(`fileKey`)으로 `<img src>` 하면 안 됨(비공개 → 403).
2. 제출 시 `fileKey` 를 그대로 보냄(라운드트립).
3. 표시는 조회 응답의 **`viewUrl`**(presigned). **3분 만료** — 화면 열 때 받은 걸 바로 렌더, 장기 캐시 금지. 만료 후 다시 보려면 재조회.
4. **재제출/수정 시 `fileKey` 를 보낼 것** — 조회로 받은 `viewUrl`(만료 URL)을 되돌려보내면 저장이 깨짐. (둘을 분리한 이유.)

---

## 2. 코스·프로필·리뷰 이미지 (공개) — 필드 변경 없음 ✅  (BE 진행 중 + 인프라)

노출/SEO 가 목적이라 **안정 공개 CDN URL** 로 서빙한다.

**계약 변경 없음** — 업로드 응답·생성요청·조회 응답 **필드 그대로**. 값만 이제 실제 열리는 CDN URL (`https://cdn.plop.cool/course/{uuid}.jpg` 형태)이 된다.
- 코스: `POST /course-images` → `fileURL`, 생성 `media[].url`, 조회 `url` — 모두 그대로.
- 프로필 사진 / 리뷰 이미지: 응답 URL 필드 그대로.

**웹 (Next.js/Vercel)**
- `next.config` `images.remotePatterns` 에 **`cdn.plop.cool`(prod) + `cdn-staging.plop.cool`(staging)** 추가 → `next/image` 가 리사이즈/WebP/AVIF 자동.

**모바일 앱 — 리사이즈/포맷 변환 (`/r/` 경로)**
앱은 `next/image` 같은 최적화가 없으니, API 응답의 원본 CDN URL을 **클라이언트가 변환 URL로 조립**해 쓴다(BE 응답은 항상 원본 URL). 규약:

```
원본(응답값):  https://cdn.plop.cool/course/<uuid>.jpg
리사이즈(앱):  https://cdn.plop.cool/r/course/<uuid>.jpg?w=400&fm=webp
              └ 도메인 뒤에 "/r/" 삽입 + 쿼리 추가
```
| 쿼리 | 의미 | 기본 |
|---|---|---|
| `w`/`h` | 목표 px(1..4000), 하나만 줘도 비율 유지 | — |
| `fm` | `webp`\|`avif`\|`jpeg`\|`png` | 원본 |
| `q` | 1..100 | 80 |
| `fit` | `cover`\|`contain`\|`inside`\|`outside`\|`fill` | `inside` |

- 권장: 썸네일/리스트 `w=400&fm=webp`, 상세 더 큰 `w`. 디바이스 DPR 곱해 요청.
- 결과는 CloudFront 엣지 캐시(영구). **원본 경로(`/course/...`)는 그대로 유지** — 변환은 별도 `/r/` 라 변환이 문제여도 원본/웹은 안전.
- 변환은 별도 인프라(리전 Lambda+sharp) — 적용 완료 시 공지. 그 전엔 원본만 사용.

**주의 (타이밍)**
- 공개 CDN 서빙은 **인프라(공개 버킷 + CloudFront + cdn 도메인) 배포 + Squarespace NS 위임**이 끝나야 실제로 열린다. 그 전까지 업로드는 성공하되 URL 이 공개 열람되지 않을 수 있음(BE 가 graceful 폴백). 인프라 적용 완료 시 별도 공지.

---

## 요약 표

| 이미지 | 등급 | FE 필드 변경 | 표시 | 추가 작업 |
|---|---|---|---|---|
| 자격증·보험 | 비공개 | `fileURL`→`fileKey` + `viewUrl` 신규 | `viewUrl`(3분 presigned) | 미리보기=로컬blob, 재제출=fileKey |
| 코스·프로필·리뷰 | 공개 | 없음 | CDN URL(영구) | 웹: next.config remotePatterns / 앱: `/r/{key}?w=&fm=webp` 변환 URL 조립 |
