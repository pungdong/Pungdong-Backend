# CLAUDE.md — certificate (학생 보유 자격증 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 신규 도메인. `Account`·`Course`/`Enrollment`·`Discipline` 을 **단방향 참조** — 그쪽은 이 패키지를 모른다.

## ⚠️ 먼저 — `instructorapplication` 의 자격증과 다른 것이다

이름이 비슷해 합치고 싶어지는데, **합치면 안 된다**:

| | `instructorapplication` 의 `ApplicationCertificate` | **이 도메인의 `StudentCertificate`** |
|---|---|---|
| 목적 | 강사 전환 **심사 자료** | 본인이 **보유를 기록**하는 자산 |
| 수명주기 | 신청에 종속(재제출 시 통째 교체) | 독립. 사용자가 개별 등록·삭제 |
| 보는 사람 | 어드민 + 본인 | **본인만** |
| 저장 prefix | `instructorCertificate/` | `studentCertificate/` |

**role 게이트도 없다** — 강사도 개인 자격으로 자격증을 보유한다. `hasRole` 로 막지 말 것.

## 무엇이 들어있나

- **컨트롤러** `StudentCertificateController` — `/certificates/**` (목록 `mine` · 단건 · 등록 · 수정 · 삭제 · 사진업로드). 매처는 `global/security/SecurityConfiguration` 의 `/certificates/**` (메서드 무관 `authenticated` — 새 메서드를 늘려도 그대로 덮인다).
- **서비스** `StudentCertificateService` — 검증·파생·스냅샷 전부 여기.
- **엔티티** `StudentCertificate`, enum `CertificateSource`(PUNGDONG/EXTERNAL), 레포 `StudentCertificateJpaRepo`.
- **스토리지** `storage/StudentCertificatePhotoStorage` + `S3…`/`Local…` — `pungdong.storage.s3.enabled` 게이트. **비공개(PII) 등급**.
- **탈퇴 파기** `StudentCertificateAnonymizationListener` — `account` 의 `AccountAnonymizedEvent` 수신.
- **레벨 enum 은 여기 없다** — `course/CertLevel` 을 **import 한다**(아래).

## 설계에서 놓치기 쉬운 것 (전부 의도)

- **`source` 는 요청 필드가 아니다.** `enrollmentId` 유무에서 서버가 파생한다. 클라이언트가 고를 수 있으면 **강사 없는 "풍덩 발급"** 이라는 모순 상태를 만들 수 있다.
- **`holderName` 도 요청 필드가 아니다.** 세션에서 파생(본인확인 실명 → 없으면 닉네임). 레포 규칙 "identity 는 세션에서, 입력에서 받지 않는다".
- **강사·강의도 클라이언트를 안 믿는다.** `enrollmentId` 로 서버가 조회해 박제한다.
- **표시명은 스냅샷이다** (`organizationName`/`organizationFullName`/`certificationDisplayName`). 등록 시점 Sanity 값을 박제 — 자격증은 불변 credential 이고(`Enrollment.tuitionSnapshot` 과 같은 철학), FE 의 카탈로그 소비가 **동기 순수함수**라 조회 때 Sanity 를 읽으면 로딩 상태가 리스트·상세로 번진다. **검증되는 건 코드**(`disciplineCode`=테이블, `level`=enum)이고 표시명은 아니다.
- **`CertLevel` 은 `course` 패키지에서 import.** 옮기지 말 것 — `sanity/CLAUDE.md` 가 **Sanity `certifications[].level` ↔ 이 enum ↔ `types.ts` union** 3자 계약으로 못박아 뒀고, 이 도메인은 네 번째 소비자일 뿐이다.
- **수정은 `PUT` 전면 교체이고 `PATCH` 는 없다** (2026-08-16 추가 — 편집 화면이 생겼다). 스칼라는 안 보내면 비워진다. 단 **두 필드는 "생략"의 뜻이 다르다**:
  - `photoFileKey` **생략 = 기존 사진 유지** (전면 교체의 유일한 예외). 사진은 별도 업로드 왕복이라, 안 그러면 번호 오타 하나 고치려고 카드를 다시 찍어야 한다. FE 가 기존 key 를 되돌려 보낼 수도 없다(응답에 오는 건 만료되는 `photoViewUrl` 이지 key 가 아니다). 교체 시 **옛 객체는 커밋 이후 파기**. 사진 *제거*는 표현 불가 — **"빈 문자열 = 제거"로 겸용하지 말 것**(생략과 구분이 안 돼 유지/제거가 뒤바뀐다).
  - `enrollmentId` **생략 = 연결 해제**(`EXTERNAL` + 강의 스냅샷 **전부** 비움. 부분 잔존 = 유령 강의).
- **사진은 필수다** (2026-08-16 선택 → 필수). "사진이 진실"이 이 도메인의 신뢰 모델인데(표시명·번호는 대조하지 않는다) 정작 사진이 선택이라 **검증의 근거가 없는 행**이 만들어질 수 있었다. 실제 확인은 수영장 입장 때 사진 제시로 이뤄진다.
  - 등록은 DTO `@NotBlank`("자격증 사진을 추가해주세요.").
  - **수정은 필드가 아니라 결과 상태를 검사한다** (`requirePhotoAfterUpdate`) — 여기 `@NotBlank` 를 걸면 위의 "생략 = 유지"가 죽어 매 수정마다 재업로드를 강요하게 된다. "요청도 비었고 기존도 없음"일 때만 400.
  - ⚠️ **DB `NOT NULL` 을 걸지 않았다.** 필수가 되기 전 사진 없이 등록된 행이 있고, 제약을 걸면 그 행이 읽기·삭제조차 막힌다(`hbm2ddl=validate` 부트 실패 포함). 옛 행은 **조회·삭제 그대로, 수정할 때만 사진 요구**. 새 규칙은 쓰기 경로에서만 강제한다.
- **`StudentCertificateUpdateRequest` 와 `StudentCertificateCreateRequest` 는 필드가 같아야 한다.** 한쪽에만 필드를 추가하면 등록은 받는데 수정은 **조용히 무시**한다. 검증은 `photoFileKey` 하나만 의도적으로 갈린다(등록 `@NotBlank` / 수정 제약 없음 + 서비스 검사). 클래스를 나눈 것도 그 *의미* 차이 때문 — 이름이 같다고 뜻까지 같지 않다.
- **엔티티에 `@Setter` 를 열지 않는다.** 열면 `source`·`enrollmentId` 같은 **서버 파생값**까지 아무 데서나 바뀐다. 의도별 메서드(`updateDetails`/`replacePhoto`/`linkCourse`/`unlinkCourse`)만 두고, `linkCourse` 가 `source=PUNGDONG` 이 되는 유일한 경로다. `owner`·`createdAt` 은 어디서도 안 바뀐다.
- **강의 연결 검증은 `applyCourseLink` 한 곳** — 등록·수정 공용. 두 벌이면 등록은 통과하고 수정은 거절하는 어긋남이 생긴다.
- **중복 등록을 막지 않는다.** 재취득·재발급(분실·갱신)이 실재한다. UNIQUE 를 걸면 정상 사용자를 막는다.
- **`enrollment_id` 에 FK 를 안 걸었다.** 연결한 수강이 나중에 정리돼도 자격증(사용자 자산)은 남아야 한다.

## 강의 연결 — 판정을 공유한다

`enrollmentId` 는 **소유 + 완료 + 종목 정합** 3중 검증을 거친다. 완료 판정은 **`EnrollmentCompletion.isCertifiable`** 하나를 쓰고, **hub 응답도 같은 값을 `certifiable` 로 노출**한다. FE 피커는 그 필드로 거른다.

> 🔴 **"hub 의 `status === 'COMPLETED'`" 로 판정하지 말 것 — 그건 다른 질문이다.**
> 정규 회차를 다 끝낸 뒤 **추가세션(EXTRA)** 을 잡으면 그 회차가 결제대기라 카드 상태는 `PROGRESS` 로 돌아간다. 하지만 **자격증은 이미 취득한 것**이다. 표시용 상태로 판정하면 그 동안 피커에서 강의가 사라진다(리뷰에서 잡힌 실제 경로).
> → `certifiable`(정규 전부 done) 과 `status`(카드 표시)는 **일부러 분리**돼 있다. 합치지 말 것.
> `CourseScheduleStatus.derive()` 단독도 안 된다 — "잡힌 회차"만 봐서 3회차 중 1회차만 듣고 끝낸 수강도 완료로 본다. 코스의 **정규 회차 수**와 대조해야 한다.

단체(`organizationCode`) 정합은 **검사하지 않는다** — 코스의 단체는 "목표 단체"라 실제 발급 단체가 다를 여지가 있다(제휴 발급). 종목처럼 구조적 모순이 아니다.

## 사진 = PII

`docs/features/image-storage-and-serving.md` §1 이 **"비공개(PII) = 자격증·보험"** 으로 등급을 못박아 뒀고, 학생 자격증은 **대상이 강사보다 훨씬 많다.**

- **사진은 필수**다(위 참조) — 다만 DB 제약이 아니라 쓰기 경로에서만 강제하므로, **읽는 코드는 여전히 null 을 다뤄야 한다**(옛 행).
- 비공개 버킷 + key 저장, 조회 시점에만 **presigned GET(TTL 3분)**. 공개 CDN 경로를 쓰지 않는다.
- 업로드는 **`ImageUploadPolicy.validate`** 를 먼저 통과(빈 파일·MIME allowlist·8MB).
- **`photoFileKey` 소유 검증 필수** — presigned URL 은 경로에 key 를 담아, 유출된 URL 에서 key 를 뽑아 자기 자격증에 붙이면 TTL 이 무의미해진다.
- 삭제·탈퇴 파기 시 **`deletePrivateObject`** 를 쓴다. ⚠️ **`deletePublicObject` 재사용 금지** — 공개 버킷 기준 환원이라 비공개 key 에 쓰면 엉뚱한 버킷을 조용히 지운 척한다(§4b 사고와 같은 모양).
- 로컬 구현도 경로에 `{ownerId}/` 를 넣는다 — 안 그러면 소유검증·일괄삭제를 dev 에서 **검증할 수 없다**.

## 작업 전 반드시 읽기

- **[docs/architecture/certificate.md](../../../../../../../docs/architecture/certificate.md)** — 흐름/ER/권한 매트릭스
- **[docs/features/image-storage-and-serving.md](../../../../../../../docs/features/image-storage-and-serving.md)** — 이미지 접근 등급 정책
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 안전망 테스트

`src/test/.../usecase/StudentCertificateUseCaseTest` — 실 H2 + 실 시큐리티 + **실제 로컬 스토리지**(mock 아님). S(성공)/V(검증거절)/R(권한)/A(탈퇴파기). 수강 픽스처는 예약 HTTP 플로우를 안 태우고 repo 로 직접 만든다(검증 대상이 자격증이지 예약이 아니다).
