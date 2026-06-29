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

### CloudFront → 변환 Lambda 인증 — 시크릿 헤더(B) 현행 / 장기 OAC 정석화

CloudFront 가 변환 Lambda(Function URL)를 호출할 때의 인증 방식. **현재 = (B) 시크릿 헤더(동작 검증됨), 장기 정석 = OAC(AWS_IAM) 복귀.**

**근본 원인 (디버깅으로 규명):** Function URL 호출이 처음에 OAC(AWS_IAM)·NONE **둘 다 403**으로 실패했다. 알고 보니 원인은 인증 방식이 아니라 **`lambda:InvokeFunction` 권한 누락**이었다 — NONE Function URL(혹은 OAC 호출)이 실제로 동작하려면 호출 주체에 **`InvokeFunctionUrl`(URL 도달) + `InvokeFunction`(함수 실행) 두 권한이 모두** 필요한데, 콘솔로 만들면 AWS 가 자동 추가하지만 **Terraform/CLI 는 둘 다 명시해야** 한다(콘솔 경고 배너가 명시). `InvokeFunctionUrl` 만 줘서 계속 403 났던 것. (`InvokeFunction` 은 `FunctionUrlAuthType` 조건을 못 받아 조건 없이 `*` 부여.)

**현행 (B) 시크릿 헤더 — 동작 검증됨:** Function URL `AuthType=NONE` + CloudFront 가 origin custom header 에 **비밀값** 주입 + Lambda 가 그 헤더 없으면 403. staging 에서 end-to-end 확인(`/r/{key}?w=&fm=webp` → 200 webp, 엣지 캐시 Miss→Hit, 원본 무영향).
- **왜 이걸로 시작**: OAC 가 막힌 줄 알고(원인 미규명 상태) 채택. (1) 변환은 **공개 이미지 전용** — 비공개/PII(자격증)는 presigned 라 CloudFront 를 아예 안 탐(별개). (2) 최악(시크릿 유출)에도 노출은 **이미 공개된 이미지** + 호출 비용뿐(IAM 롤=공개 버킷 GetObject 만). (3) 스타트업 단계 리스크 낮음 → over-engineering 회피. (사용자 결정 2026-06-30.)
- **B 의 약점**: Function URL 이 형식상 public 도달 가능(시크릿=앱강제). 스캐너에 "미인증 엔드포인트"로 잡힘. AWS-강제가 아니라 *앱-강제* 라 정석 대비 열위. + `InvokeFunction` 을 `*` 에 (조건 없이) 부여 — 단, Invoke API 직접 호출도 헤더 없어 403.

**장기 정석 — OAC(AWS_IAM) 복귀 (Lambda@Edge 보다 우선):** 근본 원인이 권한 누락이었으므로, **OAC 도 CloudFront 주체에 `InvokeFunction` 권한만 추가하면 동작할 가능성이 높다.** OAC 면 Function URL=AWS_IAM 으로 **공개 도달 자체가 없어짐**(B 의 public+앱강제 약점 제거) — *Lambda@Edge 의 큰 rework(us-east-1·env 불가·sharp 크기) 없이* 정석에 도달. → **장기 목표를 Lambda@Edge 가 아니라 OAC 복귀로 수정.** (Lambda@Edge 는 OAC 도 끝내 안 될 때의 폴백.)
- **전환 시 계약 불변**: `/r/{key}?w=&fm=` URL 그대로 — origin 인증만 교체. 앱/FE 변경 없음.
- **왜 지금 안 하나**: B 가 이미 동작하고, OAC 전환엔 또 한 번의 CloudFront 재배포(~15분)·재검증이 필요. 공개 이미지라 B 의 잔여 리스크가 낮아 급하지 않음 — 여유 있을 때 OAC 로 하드닝.

---

## 5. 결정 히스토리

| 시점 | 결정 | 근거 | PR |
|---|---|---|---|
| 2026-06-29 | **비공개 이미지 = 비공개 버킷 + presigned(TTL 3분)** | 자격증=PII, 공개 불가; 짧은 TTL로 유출창 차단; 프록시로 하드닝 여지 | #138 |
| 2026-06-29 | **공개 이미지 = 별도 public 버킷, CloudFront(OAC) + 커스텀 도메인(prod·staging 둘 다)** | SEO=LCP/속도 + 브랜드 안정 URL; OAC로 버킷은 비공개 유지(보안); day-1 도메인 고정 → FE 계약 무변경 | (이 PR) |
| 2026-06-29 | **이미지 변환 = 리전 Lambda(ap-northeast-2) + sharp, CloudFront 뒤** (Lambda@Edge·관리형·업로드시파생 기각) | 결과를 엣지 캐시 → Lambda 는 미스에만; 한국 중심이라 엣지=리전 권역 일치로 Lambda@Edge 이득 미미 + 리전 람다가 운영/디버그 쉬움(솔로 dev); 계약 무변경(쿼리만) | (이 PR) |
| 2026-06-30 | **변환 Lambda 인증 = (B) 시크릿 헤더 (동작 검증)**, 근본원인=`InvokeFunction` 권한 누락, **장기 정석=OAC 복귀**(Lambda@Edge 아님) | OAC·NONE 둘 다 403 → 원인은 인증모드 아닌 **`lambda:InvokeFunction` 누락**(Terraform 은 InvokeFunctionUrl+InvokeFunction 둘 다 명시 필요, 콘솔은 자동); B 로 staging end-to-end 동작 확인; 원인이 권한이라 **OAC 도 그 권한 추가하면 viable → 공개 URL 없는 정석에 Lambda@Edge rework 없이 도달 가능**, 장기목표를 OAC 복귀로 수정(B 의 public+앱강제 약점 제거) | (이 PR) |

---

## 6. 미해결 / 로드맵

- 🟢 **이미지 변환**(모바일 앱 최적화) — CloudFront + 리전 Lambda + sharp, 온디맨드 리사이즈/WebP. 위 §4.
- 🟡 **변환 인증 B→OAC 하드닝** — 현재 (B) 시크릿 헤더(공개 도달+앱강제, 동작 검증됨). 근본원인이 `InvokeFunction` 권한 누락이었으므로 **OAC(AWS_IAM)로 복귀**(CloudFront 주체에 InvokeFunction 추가)하면 공개 엔드포인트 없는 정석에 도달 — Lambda@Edge 큰 rework 불필요. CloudFront 재배포·재검증(~15분)만. URL/계약 불변. 여유 시 진행. (Lambda@Edge 는 OAC 도 안 될 때 폴백.) (§4)
- 🟡 **레거시 `/lecture`·`/lectureImage` 이미지** — Course가 대체 중. 공개 버킷 전환 대상에서 제외(레거시), Course로 수렴 시 정리.
- 🟡 **커뮤니티/SNS 이미지** — 기능 도입 시 공개 버킷에 같은 패턴 적용.
- 🟡 **기존 비공개 버킷의 공개-의도 잔존물** — #138 이후 코스/프로필/리뷰가 잠시 비공개 버킷에 업로드되던 구간(서빙 불가). 이 PR로 공개 버킷 전환. (강사 부재로 staging 실데이터 영향 없었음.)
