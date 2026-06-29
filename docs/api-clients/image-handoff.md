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

**(선택) 다이빙보험 첨부** — 자격증과 **완전히 같은 패턴**, 단 **옵셔널**(필수 아님). 종목 신청별.
- 업로드: **같은 엔드포인트** `POST /instructor-applications/certificate-images` 로 보험 이미지 올려 `fileKey` 받음.
- 제출/재제출 `POST·PUT /instructor-applications` 바디에 **`insuranceFileKey`**(top-level, 옵셔널) 로 그 key 전송. 안 보내면 보험 없음(재제출 시 안 보내면 해제됨 — 유지하려면 prefill 로 재전송).
- 조회(`/me`·어드민 상세): **`insuranceFileKey`**(라운드트립) + **`insuranceViewUrl`**(presigned 3분, 표시용) 내려옴. 없으면 두 필드 미포함.
- 미리보기·만료·재제출 규칙은 위 1~4 와 동일.

---

## 2. 코스·프로필·리뷰 이미지 (공개) — 필드 변경 없음 ✅  (staging 배포·검증됨)

노출/SEO 가 목적이라 **안정 공개 CDN URL** 로 서빙한다.

**계약 변경 없음** — 업로드 응답·생성요청·조회 응답 **필드 그대로**. 값만 이제 실제 열리는 CDN URL (`https://cdn.plop.cool/course/{uuid}.jpg` 형태)이 된다.
- 코스: `POST /course-images` → `fileURL`, 생성 `media[].url`, 조회 `url` — 모두 그대로.
- 프로필 사진 / 리뷰 이미지: 응답 URL 필드 그대로.

### 변환(리사이즈/포맷) 엔드포인트 — `/r/`
원본은 `https://cdn.plop.cool/course/<uuid>.jpg`. 리사이즈/포맷은 **호스트 뒤에 `/r/` 삽입 + 쿼리**:
```
https://cdn.plop.cool/r/course/<uuid>.jpg?w=400&fm=webp&q=80&fit=inside
```
| 쿼리 | 의미 | 기본 |
|---|---|---|
| `w`/`h` | 목표 px(1..4000), 하나만 줘도 비율 유지 | — |
| `fm` | `webp`\|`avif`\|`jpeg`\|`png` | 원본 |
| `q` | 1..100 | 80 |
| `fit` | `cover`\|`contain`\|`inside`\|`outside`\|`fill` | `inside` |
결과는 CloudFront 엣지 캐시(영구·불변키). 원본(`/course/..`)은 그대로 — 변환이 문제여도 원본/웹 안전(fail-safe).
**핵심 원칙: width 는 연속이 아니라 "사다리"(고정 후보 몇 개)로** — 후보마다 캐시 엔트리가 생기므로 무한 width 는 캐시 파편화. 웹은 next/image 가, 앱은 아래 사다리로 처리.

### ✅ 웹 (Next.js/Vercel) 체크리스트
반응형은 `next/image` 가 자동(`srcset`/`sizes` 로 뷰포트+DPR 맞춰 후보 선택, 창 키우면 업그레이드/줄이면 유지 — 픽셀마다 재요청 아님). 할 일:
1. **`next.config` `images.remotePatterns`** 에 `cdn.plop.cool` + `cdn-staging.plop.cool` 추가 (없으면 next/image 거부).
2. **`<Image src>` = 원본 URL**(`/course/{key}`). `/r/` 직접 X — width 선택은 next/image 가.
3. **`sizes` 지정**(레이아웃별, 예 `sizes="(max-width:768px) 100vw, 50vw"`) — 브라우저 후보 선택 근거.
4. **결정 1개 — next/image 로더 전략**:
   - (a) Vercel 기본 옵티마이저 — CDN URL 을 소스로 Vercel 이 리사이즈/WebP/AVIF. 단순. (우리 `/r/` 미사용, 비용=Vercel 최적화 사용량)
   - (b) 커스텀 로더 → 우리 `/r/?w=&fm=webp` — 최적화를 우리 CloudFront/Lambda 로 오프로드(Vercel 비용↓). 트래픽 보고 FE 가 택.
5. **og:image / sitemap / 구조화데이터**: 크롤러는 next/image 안 거침 → **고정 사이즈 하나** 박기(예 `/r/{key}?w=1200&fm=jpeg`).

### ✅ 앱 (네이티브) 체크리스트
슬롯이 고정이라 단순하지만 3가지:
1. **URL 조립**: API 의 원본 URL → 호스트 뒤 `/r/` 삽입 + `?w=&fm=webp`.
2. **width = 슬롯 pt × 기기 DPR, 사다리로 스냅**. "고정 슬롯"이라도 (1) 2x/3x 기기마다 px 다름, (2) 썸네일/상세/풀 슬롯 다름. 원본 그대로 받으면 변환 무의미, DPR 무시하면 레티나 흐림. **권장 사다리: 200·400·800·1200·1600** (올림). → 캐시 공유·유한.
3. **포맷 `fm=webp`** (iOS14+/안드 지원). 자신 있으면 `avif`.

### 상태
공개 CDN(원본 + `/r/` 변환) **staging 배포·검증 완료**(`cdn-staging.plop.cool`). prod(`cdn.plop.cool`)는 BE 가 동일 적용. (인증=OAC, 직접 호출 차단·CloudFront 만.)

---

## 요약 표

| 이미지 | 등급 | FE 필드 변경 | 표시 | 추가 작업 |
|---|---|---|---|---|
| 자격증·보험 | 비공개 | `fileURL`→`fileKey` + `viewUrl` 신규 | `viewUrl`(3분 presigned) | 미리보기=로컬blob, 재제출=fileKey |
| 코스·프로필·리뷰 | 공개 | 없음 | CDN URL(영구) | 웹: remotePatterns+sizes+로더결정+og:image / 앱: `/r/{key}?w=&fm=webp` (w=슬롯pt×DPR, 사다리 200/400/800/1200/1600) |
