# 이미지 저장·서빙 (image storage & serving)

> 여러 도메인(강사신청·**학생 자격증**·코스·프로필·리뷰)에 걸친 **교차 피처**. 이 문서는 *정책·왜·결정 히스토리*를 소유한다. 구현(어댑터·엔드포인트)은 각 도메인 문서로 링크한다.
> 관련 메모리: `s3_image_access_classes`.

## 한 줄 요약

이미지는 **접근 등급(access class)** 으로 두 갈래다 — **비공개(PII)** 는 비공개 버킷 + presigned, **공개(노출용)** 는 CloudFront(OAC) + 커스텀 도메인. **한 버킷에 섞지 않는다.** 등급이 정반대의 요구(은닉 vs 영구공개·SEO)를 갖기 때문.

**PII 이미지의 수명주기(업로드·열람·소유검증·삭제·탈퇴 파기·dev 폴백)는 §4c 가 단일 출처다.** 강사 자격증·보험과 학생 보유 자격증에 동일하게 적용된다 — 도메인마다 다르게 구현되지 않도록 정책을 한 곳에 둔다.

---

## 1. 두 접근 등급과 그 근거

| 등급 | 대상 | 요구사항 | 저장/서빙 | 버킷 |
|---|---|---|---|---|
| **비공개 (PII)** | **강사** 자격증·보험 (`instructorCertificate/{accountId}/`) | 어드민·본인만, 은닉 | 객체 key 저장, 조회 시 **presigned GET(TTL 3분)** | `plop-{env}-uploads` (BPA 4종 ON) |
| **비공개 (PII)** | **학생 보유 자격증** (`studentCertificate/{accountId}/`) — 실물 카드 촬영본(이름·자격증번호) | **본인만**(어드민도 안 봄) | 위와 동일 | 위와 동일 |
| **공개 (노출)** | 코스·프로필·리뷰 (+ 향후 커뮤니티) | 영구·안정 공개 URL, **SEO**, 빠른 로딩 | 완성된 **CDN URL** 저장·반환 | `plop-{env}-public` (BPA 유지·비공개, **CloudFront OAC로만 노출**) |

> ⚠️ **학생 자격증이 이 등급의 최대 물량이다.** 강사 자격증은 심사 1회용이고 대상이 강사뿐이지만, 학생 자격증은 **전 사용자가 여러 장**을 보유·등록한다. 같은 등급이되 사고 시 영향 범위가 훨씬 크다 — PII 처리 누락이 생기면 여기서 먼저 커진다.

**왜 한 버킷에 안 섞나** — 버킷 단위로 공개정책/BPA가 갈리고, 같은 버킷에 PII와 공개물을 섞으면 prefix 정책 오설정 한 번에 개인정보가 노출된다. 버킷으로 물리 분리하는 게 사고 표면을 없앤다.

---

## 2. 비공개(PII) — presigned 선택 근거 (PR #138, 2026-06-29)

자격증/보험 이미지는 개인정보. 공개 URL로 두면 안 된다.

- **버킷 비공개 + 객체 key 저장**(`instructorCertificate/{accountId}/{uuid}` · `studentCertificate/{accountId}/{uuid}`). 회원별 그룹핑 → 탈퇴 PII 익명화 시 prefix 일괄 삭제(**2026-08-14 #254 로 실제 구현됨** — 그 전까지는 설계 의도만 있고 코드가 없었다, §4c). 키에 PII(이메일) 없음.
- **조회 시점에만 presigned GET(TTL 3분) 발급** — 어드민/본인 응답에서.
- **왜 presigned (대안: 공개 URL / 인증 프록시):**
  - 비공개 버킷이라 키를 알아도 **SigV4 서명 없이는 403** — 추측·열거로 못 연다. 위조는 암호학적으로 불가.
  - 유출 위험은 *발급된 URL 문자열*이 로그/Referer/공유로 새는 것뿐 → **짧은 TTL이 그 창을 닫는다.** 저빈도(심사 1회) PII 열람엔 업계 표준.
  - **인증 프록시**(매 요청 세션검증 후 바이트 스트림)는 더 강하지만(베어러 URL 없음·매요청 인가·즉시 철회) BE 대역폭/엔드포인트 비용. 출시 시점 강사 온보딩엔 과함. 단 `CertificateImageStorage.viewUrl` 구현만 바꾸면 **계약 변경 없이** 프록시로 하드닝 가능(여지 남김).
- 구현: 강사 = [architecture/instructor-application.md](../architecture/instructor-application.md) · 학생 = [architecture/certificate.md](../architecture/certificate.md).

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

### 이미지 변환 — 온디맨드 리사이즈/포맷 (CloudFront + 리전 Lambda + sharp)

`cdn.plop.cool/r/{key}?w=400&fm=webp` 식 **온디맨드** 리사이즈/포맷 변환을 CloudFront 뒤에 둔다.
모바일 앱은 Vercel `next/image` 같은 최적화 레이어가 없어 **BE/엣지가 최적화를 제공해야** 하기 때문(웹은 `next/image`가 처리). **앱이 웹보다 실사용 가치가 커서** 이 변환은 앱을 위해 우선한다.

- **변환은 전용 경로 `/r/*` (fail-safe 라우팅)**: 원본은 기존 `{cdn}/{key}` (S3 origin, #141 그대로) — **웹/SSG/og:image 계약 불변**. 리사이즈는 `{cdn}/r/{key}?w=&h=&fm=&q=` 로 **CloudFront 의 별도 cache behavior → 변환 Lambda** 가 처리. CloudFront 는 origin 을 path 로 라우팅(쿼리로는 못 함)하고, 무엇보다 **변환 Lambda 가 깨져도 원본/웹은 영향 없다**(검증된 S3 origin 을 blind 로 갈아끼우지 않음). 앱은 코드로 URL 을 만드니 `/r/` prefix 부담 없음.

**메커니즘 — 리전 Lambda(ap-northeast-2) + sharp 선택 (대안 기각):**
- **(택1) 리전 Lambda + sharp, CloudFront 뒤** — CloudFront 가 변환 결과를 **엣지 캐시**하므로 Lambda 는 *캐시 미스에만* 실행된다. 이미지는 **UUID 불변 키 + 긴 TTL** 이라 미스가 드물고, 인기 변형(예 카드 400w)은 첫 요청 후 엣지 히트. 한국 중심 서비스라 **서울 엣지 + 서울 리전 origin** 이 같은 권역 → 미스 경로 hop 도 짧다.
- **(기각) Lambda@Edge** — 엣지 실행으로 "엣지→리전" hop 을 아끼지만, 그 이득은 **사용자가 origin 리전에서 멀 때**만 큼. 서울 사용자 + 서울 origin 이면 차이 미미한데, **us-east-1 배포·env 변수 불가·디버그 난이도**라는 운영비용이 솔로 dev 엔 더 크다. (글로벌 확장 시 전환 가능 — URL/계약 불변.)
- **(기각) AWS 관리형 솔루션 그대로** — 자체 CloudFront 배포를 또 들고 와 우리 것과 중복.
- **(기각) 업로드 시 고정 파생 N개** — 임의 사이즈 불가·업로드 지연·재처리 부담 + Java WebP 네이티브 의존.

**왜 변환 결과가 빨라도 되나(레이턴시)**: 사용자 체감은 *캐시 히트*(엣지, a/b 동일)가 지배한다. Lambda 실행은 미스에만 발생하고 그 결과는 즉시 캐시 → 1회성. 변환 응답에 긴 `Cache-Control` 을 박아 미스 빈도를 더 낮춘다.

### CloudFront → 변환 Lambda 인증 — OAC(AWS_IAM), 정석 (구현·검증됨)

**채택 = OAC.** Function URL `AuthType=AWS_IAM` + CloudFront OAC(SigV4)로만 호출 → **공개 도달 자체가 없음**(직접 호출은 서명 없으면 403). 권한은 `cloudfront.amazonaws.com` 주체에 SourceArn=배포 조건으로 `InvokeFunctionUrl` + `InvokeFunction` **둘 다** 부여. staging end-to-end 검증: `/r/{key}?w=200&fm=webp` → 200 webp 668B, `?fm=jpeg`/`?h=` 정상, **직접(미서명) 호출 403**(=공개 차단), 엣지 캐시 Hit, 원본 무영향.

**근본 원인 (반나절 잡아먹은 교훈):** OAC 도 NONE 도 처음엔 **둘 다 403**이라 "비-us-east-1 OAC SigV4 문제"로 오판하고 OAC 를 접었다. 진짜 원인은 인증 방식이 아니라 **`lambda:InvokeFunction` 권한 누락**이었다 — Function URL 호출이 실제로 동작하려면 호출 주체에 **`InvokeFunctionUrl`(URL 도달) + `InvokeFunction`(함수 실행) 두 권한이 모두** 필요한데, 콘솔로 만들면 AWS 가 자동 추가하지만 **Terraform/CLI 는 둘 다 명시해야** 한다(콘솔 경고 배너가 명시). `InvokeFunctionUrl` 만 줘서 계속 403. (OAC 의 InvokeFunction 은 `SourceArn` 조건으로 CloudFront-only 유지. NONE 일 땐 `FunctionUrlAuthType` 조건이 InvokeFunction 에 안 붙어 `*` 였음.)

**중간에 거쳤던 (B) 시크릿 헤더 — 폐기:** 원인 미규명 상태에서 임시로 Function URL=NONE + CloudFront 가 `x-origin-secret` 헤더 주입 + Lambda 검증으로 우회해 동작시켰다. 그러나 Function URL 이 형식상 public 도달 가능(시크릿=*앱*-강제)이라 정석 대비 열위. 근본 원인을 찾은 뒤 **OAC(=*AWS*-강제, 공개 도달 없음)로 교체**해 폐기. (B 의 잔여 리스크는 공개 이미지 전용이라 어차피 낮았음 — 비공개/PII 는 presigned 로 CloudFront 미경유.)

**Lambda@Edge(C) 는 불필요해짐:** C 의 유일한 매력이 "공개 엔드포인트 제거(AWS 강제)"였는데 OAC 가 그걸 이미 달성. C 가 OAC 보다 나은 건 *엣지 컴퓨트 = 캐시 미스 레이턴시*뿐인데, 한국 중심(서울 엣지=서울 리전 origin)이라 무의미. → **C 는 글로벌 확장으로 엣지 레이턴시가 중요해질 때의 폴백으로만** 남김(us-east-1·env 불가·sharp 크기라 셋업 비용 큼).

---

---

## 4b. 업로드·삭제 정책 (2026-08-10)

### 업로드 — 공개 이미지는 타입 allowlist + 크기 상한
공개 이미지는 CloudFront 로 **그대로 서빙**되고, `S3Uploader` 는 클라이언트가 준 `Content-Type` 을 S3 메타데이터에 복사한다. **업로드 시점이 유일한 차단 지점**이라 여기서 막지 않으면 위조한 타입이 그대로 브라우저에 내려간다. `global/validation/ImageUploadPolicy` 가 공개 업로드 경로 3곳(프로필·코스·리뷰)에서 S3 를 건드리기 전에 검사한다.

- 허용 MIME: `image/jpeg` · `image/jpg` · `image/png` · `image/webp` — **변환 Lambda 의 sharp 가 다루는 포맷**에 맞춘다(HEIC 미지원이라 제외). `Content-Type` 부재는 거부(검증 불가한 값을 통과시키지 않는다).
- 크기 상한 **8MB** — 단순 방어가 아니다. `/r/*` 변환 Lambda 가 원본을 메모리에 올린 뒤 결과를 **base64 로** 반환해 Function URL 페이로드 상한(~6MB)에 걸리므로, 원본이 과도하게 크면 **썸네일이 아예 안 만들어진다.** (그래서 FE 는 `/r/` 를 항상 `w`/`fm` 과 함께 부른다 — 파라미터 없는 passthrough 는 원본을 base64 로 싣는다.)
- 비공개(자격증·보험)와 레거시 lecture 이미지는 별도 스토리지 인터페이스를 타고 공개 서빙 경로가 아니라 이번 범위에서 제외.

### 삭제 — 저장값 3종을 (버킷, key) 로 환원
저장값 포맷이 시대별로 **완성 CDN URL / S3 객체 URL / 맨 파일명** 3종이라 그대로 key 로 쓸 수 없다. `S3Uploader.deletePublicObject` 가 셋을 각각 환원한다. **공유 기본 이미지(`ProfilePhoto.DEFAULT_IMAGE_URL`)를 거르는 건 값의 의미를 아는 호출처 책임** — 특정 개인의 사진이 아니라 지우면 안 된다.

### 삭제 — 비공개(PII)는 별도 경로 (2026-08-14)

**비공개 객체엔 삭제 수단이 아예 없었다.** `S3Uploader` 에는 `deletePublicObject` 하나뿐이라, `instructorCertificate/` 에 올라간 자격증·보험 이미지는 **어떤 경로로도 지워지지 않았다.**

- `deletePrivateObject(key)` — 저장값이 곧 key 인 비공개 객체 단건 삭제.
- `deletePrivateObjectsUnderPrefix(prefix)` — 회원 단위 일괄 삭제(1000건 페이지네이션 추적).
- **`deletePublicObject` 를 비공개 key 에 쓰면 안 된다** — 공개 버킷 기준으로 (버킷,key)를 환원하므로 엉뚱한 버킷을 지우려 들고, S3 는 없는 key 에도 204 라 **조용히 성공한 것처럼 보인다**(아래 실버그와 같은 실패 모양).

**탈퇴 익명화가 자격증 이미지를 지우지 않고 있었다**(→ #254 로 해결) — §2 가 회원별 그룹핑(`{dir}/{accountId}/`)의 근거로 든 "탈퇴 시 prefix 일괄 삭제"가 **구현된 적이 없었다.** 아래 프로필 사진 사고와 **같은 구멍**이 자격증 쪽에 남아 있었던 것. 이제 `AccountAnonymizationService` 가 `AccountAnonymizedEvent` 를 발행하고 각 도메인 리스너가 자기 저장소를 정리한다. 잔존분 소급 정리도 불필요함이 실측됐다(§4c).

- **왜 이벤트인가**: 파기 대상이 도메인마다 흩어져 있는데 **account 는 feature 도메인을 import 하지 않는다**(단방향 규칙 — `profile` 패키지가 존재하는 이유와 같은 제약). 새 도메인은 account 를 건드리지 않고 **리스너만 추가**한다.
- **리스너의 실패 처리는 대상마다 다르다** — 사진(외부 객체)은 삼키고, **DB 행은 던진다.** 행 자체가 PII 라 삼키면 `anonymizedAt` 만 찍힌 채 남는다. 근거와 함정(`@Transactional`·`REQUIRES_NEW` 금지)은 **§4c** 가 소유한다. *(초판은 "리스너는 예외를 삼킨다" 로만 적어 행까지 삼키는 것으로 읽혔다 — #255 에서 정정.)*
- 로컬(dev) 구현도 경로에 `{ownerId}/` 를 넣도록 맞췄다 — 예전엔 평탄 저장이라 소유자 정보가 경로에 없어서 **삭제·소유검증을 dev/테스트에서 검증할 수 없었다**(= prod 에서만 도는 코드).

> 🔴 **왜 지금 고쳤나 (실버그)** — 예전 `deleteFileFromS3` 는 어떤 값이든 **비공개 버킷의 key** 로 취급했다. 공개 버킷 전환(#140) 이후 프로필 사진 저장값은 CDN URL 이라 **존재하지 않는 버킷·key 를 지우고 있었다.** S3 `deleteObject` 는 없는 key 에도 204 를 주고 호출부는 `log.warn` 이라 **완전 무증상** — 결과적으로 **탈퇴 익명화가 얼굴 사진(PII)을 그대로 남겼다.** 개인정보 파기 의무 위반이라 브랜딩 페이지 작업과 분리해 선행 수정했다.
> 함께: `ProfilePhotoService` 가 교체된 옛 사진을 안 지워 **S3 고아**가 쌓이던 것도 수정(업로드 성공 뒤에만 삭제 — 실패해도 새 사진은 남는다).

---

## 4c. 비공개(PII) 이미지 — 상황별 처리 (단일 출처)

> 이 표가 **PII 이미지 수명주기의 단일 출처**다. 강사 자격증·보험(`instructorCertificate/`)과 학생 보유 자격증(`studentCertificate/`)에 **동일하게** 적용된다. 도메인별 구현은 [instructor-application.md](../architecture/instructor-application.md) · [certificate.md](../architecture/certificate.md).

| 상황 | 처리 | 실패하면 |
|---|---|---|
| **업로드** | `ImageUploadPolicy.validate` (빈 파일 · `image/jpeg`·`jpg`·`png`·`webp` · **8MB**) → **비공개 버킷**. 공개 URL 은 생성되지 않는다 | 400 + 한국어 메시지(FE 가 그대로 노출) |
| **열람** | 조회 시점에 **presigned GET(TTL 3분)** 발급. 저장값은 key 지 URL 이 아니다 | — |
| **열람 갱신** | TTL 이 짧아 목록에서 받은 URL 이 만료될 수 있다 → 단건 조회가 재발급(`GET /certificates/{id}`) | FE 가 이미지 로드 실패 시 1회 재조회 |
| **제출(fileKey 라운드트립)** | **소유 검증 필수** — 저장 참조가 `{dir}/{내 accountId}/` 를 포함해야 한다 | 400(사유 미특정) |
| **사용자 삭제** | DB 행 삭제 → **커밋 이후** `deletePrivateObject` | 사진 삭제 실패는 **삼킨다**(고아 1개 잔존). 행은 이미 지워졌다 |
| **탈퇴 익명화** | `AccountAnonymizedEvent` → 각 도메인 리스너가 **행 삭제 + prefix 일괄 삭제** | **행 삭제는 던진다**(익명화 롤백 → 재시도) / **사진 삭제만 삼킨다** |
| **dev(`s3.enabled=false`)** | 로컬 디스크 + `/local-uploads/**` **무인증** 정적 서빙 | 알려진 한계 — §6 |

### fileKey 소유 검증이 왜 필수인가

presigned URL 은 **경로에 객체 key 를 그대로 담는다.** 그 URL 이 한 번 새면(스크린샷·CS 티켓·로그·`Referer`) 누구든 key 를 뽑아 **자기 리소스에 붙여 3분짜리 URL 을 무한 재발급**할 수 있다 — TTL 로 좁혀둔 열람 창이 사실상 영구 접근이 된다. key 가 UUID 라 추측 공격은 불가능하니 *유출된 경우에 한한* 방어지만, 비용이 문자열 비교 한 번이다.

**적용 지점 4곳**(전부 #254): 강사 신청 제출·재제출의 `certificates[].fileKey` · 같은 요청의 `insuranceFileKey` · 자격증 관리 탭 append. 학생 자격증의 `photoFileKey` 는 #255. **새로 파일 참조 필드를 추가하면 여기도 같이 추가할 것.**

> ⚠️ 로컬 구현도 저장 경로에 `{ownerId}/` 를 넣는다. 예전엔 평탄 저장이라 소유자 정보가 경로에 없었고, 그래서 **소유 검증과 일괄 삭제를 dev/테스트에서 밟을 수 없었다**(= prod 에서만 도는 코드). 그리고 이 검사는 `..` 을 먼저 거부해야 한다 — 안 그러면 로컬에서 경로 이탈로 임의 파일 삭제가 된다.

### 행 삭제와 사진 삭제의 실패 처리가 다른 이유

탈퇴 익명화 리스너에서 **둘을 한 `catch` 로 묶으면 안 된다.**

- **행 = PII 그 자체.** 삭제 실패를 삼키면 `anonymizedAt` 만 찍힌 채 데이터가 남고, `AccountAnonymizationService` 의 **멱등 가드가 재시도까지 막아** 영구히 안 지워진다. 던져서 익명화 전체를 롤백시키고 다음 스윕에 다시 시도하게 하는 게 맞다.
- **사진 = 외부 객체.** 스토리지 장애로 PII 파기 자체가 무산되는 건 과하다 — 실패해도 남는 건 **고아 객체 1개**고 행은 이미 지워졌다.

즉 **"고아 1개 < 파기 실패" 의 고아는 S3 객체지 DB 행이 아니다.**

> 리스너에 `@Transactional` 을 붙이지 말 것 — 기본 `@EventListener` 는 발행자 트랜잭션 안에서 동기 실행되므로 `REQUIRED` 는 no-op 이고, JPA 예외는 catch 해도 rollback-only 마킹이 안 풀려 커밋에서 `UnexpectedRollbackException` 이 난다("삼키면 계속 진행"이 거짓말이 된다). `REQUIRES_NEW` 도 금물 — 부모가 `account` 행에 X락을 쥔 상태에서 자식 삭제가 FK 확인용 S락을 원해 lock-wait 로 간다.

### 소급 정리 — 확인 완료, 추가 조치 불요

#254 **이전**에 탈퇴·익명화가 끝난 계정은 자격증 이미지가 남아 있을 수 있었다(파기 코드가 없었으므로). **2026-08-14 prod `plop-prod-uploads` 버킷이 전체 빈 상태임을 실측** — 잔존 이미지 **0건**, 일회성 정리 불필요.

---

## 5. 결정 히스토리

| 시점 | 결정 | 근거 | PR |
|---|---|---|---|
| 2026-06-29 | **비공개 이미지 = 비공개 버킷 + presigned(TTL 3분)** | 자격증=PII, 공개 불가; 짧은 TTL로 유출창 차단; 프록시로 하드닝 여지 | #138 |
| 2026-06-29 | **공개 이미지 = 별도 public 버킷, CloudFront(OAC) + 커스텀 도메인(prod·staging 둘 다)** | SEO=LCP/속도 + 브랜드 안정 URL; OAC로 버킷은 비공개 유지(보안); day-1 도메인 고정 → FE 계약 무변경 | (이 PR) |
| 2026-06-29 | **이미지 변환 = 리전 Lambda(ap-northeast-2) + sharp, CloudFront 뒤** (Lambda@Edge·관리형·업로드시파생 기각) | 결과를 엣지 캐시 → Lambda 는 미스에만; 한국 중심이라 엣지=리전 권역 일치로 Lambda@Edge 이득 미미 + 리전 람다가 운영/디버그 쉬움(솔로 dev); 계약 무변경(쿼리만) | (이 PR) |
| 2026-08-10 | **공개 이미지 삭제를 (버킷,key) 환원으로 수정** + 프로필 사진 교체 시 옛 객체 삭제 | 공개 버킷 전환 후 삭제가 **무증상으로 아무것도 안 지워** 탈퇴 익명화가 PII(얼굴 사진)를 남겼다. S3 는 없는 key 에도 204 | (이 PR) |
| 2026-08-10 | **공개 업로드에 타입 allowlist + 8MB 상한** (`ImageUploadPolicy`) | 공개 서빙이라 업로드가 유일한 차단 지점(위조 타입이 그대로 CDN 서빙됨). 8MB 는 변환 Lambda 의 base64 페이로드 상한(~6MB) 때문 | (이 PR) |
| 2026-08-14 | **비공개 삭제 신설(`deletePrivateObject`/prefix) + 탈퇴 익명화에 자격증 이미지 파기 연결**(`AccountAnonymizedEvent` → 도메인 리스너) | §2 가 회원별 그룹핑의 근거로 든 prefix 일괄 삭제가 **구현된 적이 없었다** — 프로필 사진 사고와 동일한 개인정보 파기 의무 위반이 자격증 쪽에 남아 있었음. 애초에 비공개 객체 삭제 API 자체가 부재 | (이 PR) |
| 2026-08-14 | **제출 JSON 의 `fileKey` 소유 검증**(`CertificateImageStorage.isOwnedBy`) | presigned URL 은 경로에 key 를 담아, 유출된 URL 에서 key 를 뽑아 자기 신청에 붙이면 **TTL 3분이 영구 접근**이 된다. 비용은 문자열 비교 1회 | #254 |
| 2026-08-14 | **학생 보유 자격증(`studentCertificate/`)을 같은 PII 등급으로 편입** + **§4c 상황별 처리표를 단일 출처로 신설** | 이 문서가 학생 자격증을 몰랐다(grep 0건). 같은 등급인데 **대상 규모가 훨씬 크다** — 정책이 한 곳에 없으면 도메인마다 다르게 구현된다 | #255 |
| 2026-08-14 | **행 삭제는 던지고 사진 삭제만 삼킨다**로 정정 | 초판의 "리스너는 예외를 삼킨다"가 행까지 포함하는 것으로 읽혔다. **행 자체가 PII** 라 삼키면 `anonymizedAt` 만 찍히고 멱등 가드가 재시도를 막아 영구 잔존 | #255 |
| 2026-08-14 | **소급 정리 불요 확인** — prod `plop-prod-uploads` 전체 빈 상태 실측 | #254 이전 탈퇴자의 잔존 이미지가 있을 수 있었으나 실제 0건 | (리드 실측) |
| 2026-06-30 | **변환 Lambda 인증 = OAC(AWS_IAM), 정석 (구현·검증)**. 근본원인 규명 = `lambda:InvokeFunction` 권한 누락. **Lambda@Edge(C) 불필요.** | OAC·NONE 둘 다 403 → 원인은 인증모드 아닌 **InvokeFunction 누락**(Terraform 은 InvokeFunctionUrl+InvokeFunction 둘 다 명시 필요, 콘솔은 자동). 임시로 (B)시크릿헤더로 동작시켰다가, 원인 규명 후 **OAC(공개 도달 없음=정석)로 교체**해 B 폐기. C 의 이점(엣지 레이턴시)은 한국 중심엔 무의미 → 글로벌 확장 시 폴백으로만 | (이 PR) |

---

## 6. 미해결 / 로드맵

- 🟢 **이미지 변환**(모바일 앱 최적화) — CloudFront + 리전 Lambda + sharp, 온디맨드 리사이즈/WebP, **OAC(AWS_IAM) 인증**(공개 도달 없음). 위 §4.
- ⚪ **Lambda@Edge(C)** — OAC 로 정석 달성해 **현재 불필요**. 글로벌 확장으로 엣지 컴퓨트 레이턴시가 중요해지면 그때 폴백. (§4)
- 🟡 **레거시 `/lecture`·`/lectureImage` 이미지** — Course가 대체 중. 공개 버킷 전환 대상에서 제외(레거시), Course로 수렴 시 정리.
- 🟡 **커뮤니티/SNS 이미지** — 기능 도입 시 공개 버킷에 같은 패턴 적용.
- 🟡 **알려진 데이터 이슈 — `account` 아바타에 URL 이 아닌 "맨 파일명" 이 든 레코드가 있다** (2026-08-11 발견, 커뮤니티 FE 실측)
  - 실제 값 예: `vlvkcjswo71@gmail.com2021-06-07T18:08:34.039977.png` — 구 시스템(이메일+타임스탬프로 파일명을 만들던 시절)의 잔재다. §4b "삭제 — 저장값 3종" 에 적힌 그 **3종 중 "맨 파일명"** 이 읽기 경로에도 남아 있는 경우.
  - **증상**: 그대로 렌더하면 상대경로로 해석돼 404 → 카드마다 깨진 이미지 아이콘. 커뮤니티 피드처럼 아바타가 여러 개 깔리는 화면에서 특히 눈에 띈다.
  - **현재 대응**: FE 가 렌더 가능한 URL 인지 검사해 **이니셜 폴백**으로 떨어뜨린다(깨진 아이콘보다 낫다). 다만 이건 **표시용 방어일 뿐 데이터 정정이 아니다.**
  - **남은 일**: 저장값 자체를 정규화하는 일회성 마이그레이션. ⚠️ 파일명만 있는 값은 **어느 버킷의 어느 prefix 였는지 정보가 없어** 기계적으로 URL 로 복원할 수 없다 — 실제 객체 존재 여부를 확인해 복원할지, 아니면 기본 아바타로 비울지 정하는 판단이 선행돼야 한다. 프로덕션에 같은 형태가 몇 건인지도 미확인(prod DB 읽기 경로 부재 — 닉네임 UNIQUE 작업과 같은 제약).
  - 커뮤니티 범위 밖이라 그 트랙에서는 손대지 않았다.
- 🟡 **dev 모드에서 PII 가 무인증 서빙된다** — `pungdong.storage.s3.enabled` 가 false/미설정이면 로컬 디스크 + `/local-uploads/**`(`SecurityConfiguration.webSecurityCustomizer` 의 `web.ignoring()`) 로 **TTL·서명 없이** 실명·자격증번호 사진이 열린다. 배포 두 env 는 모두 `true` 라 dev 한정이지만 **조용한 폴백**이다 — prod/staging 프로파일에서 flag 를 fail-fast 로 강제하는 게 후속(전 도메인 스토리지에 영향 가는 전역 변경이라 피처 PR 과 분리).
- 🟡 **업로드 후 미제출 고아 사진** — `POST …/photos` 로 올리고 폼을 버리면 객체가 남는다. 소유자 스코프 정리 경로(삭제·탈퇴) 어디에도 안 걸린다. 강사·학생 두 도메인 공통. 정리 배치가 후속.
- 🟡 **`S3…PhotoStorage` 테스트 커버리지 0** — 테스트는 로컬 구현만 탄다. 두 구현은 **저장 참조 모양이 다르다**(로컬=서빙 URL / S3=맨 key)라 `isOwnedBy` 가 key 형태로는 한 번도 검증되지 않는다. staging 수동 확인이 현재 유일한 안전망.
- 🟡 **기존 비공개 버킷의 공개-의도 잔존물** — #138 이후 코스/프로필/리뷰가 잠시 비공개 버킷에 업로드되던 구간(서빙 불가). 이 PR로 공개 버킷 전환. (강사 부재로 staging 실데이터 영향 없었음.)
