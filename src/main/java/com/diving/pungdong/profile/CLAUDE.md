# CLAUDE.md — profile (마이페이지 프로필 조합)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 조합(composition) 패키지. `account`(기본정보·프로필사진)와 `instructorapplication`(승인 자격)을 **단방향 참조**해 합성한다 — 둘 다 이 패키지를 모른다.

## 무엇이 들어있나

- **`ProfileController`** — `GET /account/profile`(인증·본인). `EntityModel` 단건.
- **`ProfileService`** — `AccountJpaRepo` + `InstructorApplicationJpaRepo` 합성. `@CurrentUser` 는 detach 라 LAZY(profilePhoto) 위해 트랜잭션 안에서 account 재로드.
- **`dto/AccountProfileResponse`** — `AccountBasicInfo`(id/email/nickName/roles) + `profilePhotoUrl` + `certs[]`(승인 자격 뱃지). 비강사는 `certs=[]`.

## 왜 account 패키지가 아니라 여기인가 (핵심)

자격(`certs`)이 `instructorapplication` 소유라 합성하려면 account + instructorapplication **둘 다** 의존해야 한다. 그런데 루트 규칙은 **"account 는 feature 도메인을 import 하지 않는다(단방향)"** — `instructorapplication → account` 가 이미 있어 `account → instructorapplication` 을 더하면 사이클이다. 그래서 합성을 **별도 `profile` 패키지로 빼서** `profile → {account, instructorapplication}` 단방향을 유지한다. (URL 은 `/account/profile` 이지만 클래스는 여기 산다 — Spring 은 패키지 무관.)

## 범위 밖 (데이터 모델 부재 — 의도적 제외)

- **career(경력)** — 저장 필드·온보딩 입력 없음. 보류(추후 'N년차' UI 필요성 재검토).
- **rating(평점)** — 강사 단위 집계 없음(리뷰는 레거시 Lecture 단위). **V2 에서 새 Course 리뷰 평균으로 신설 예정.**
- **자격 level/ratingCode** — `ApplicationCertificate` 에 아직 없음(주석에 "향후" 명시). 추가되면 `CertBadge` 에 노출.

## 작업 전

- 컨트롤러 시그니처/응답 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts)**(`AccountProfileResponse`) 갱신.
- 권한 매트릭스는 [docs/architecture/instructor-application.md](../../../../../../../docs/architecture/instructor-application.md) 표(자격 데이터가 거기 소유).

## 안전망 테스트

`src/test/.../usecase/AccountProfileUseCaseTest` — 실 H2 + 시큐리티. A1 강사(certs) / A2 학생(빈 certs) / A3 미승인 제외 / A4 비로그인 401.
