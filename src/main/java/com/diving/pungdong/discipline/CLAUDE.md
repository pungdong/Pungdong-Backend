# CLAUDE.md — discipline (종목 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> 종목(프리다이빙/스쿠버/수영/서핑 ...)은 홈 셀렉터 · 강사 신청 · (추후) 강의가 참조하는 1급 개념. **BE 테이블**이 source of truth.

## 무엇이 들어있나

- **컨트롤러**: `DisciplineController` — `GET /disciplines` (공개, 활성 종목 정렬 순)
- **서비스**: `DisciplineService` — 목록 + `getActiveByCode`(강사 신청 검증용)
- **엔티티**: `Discipline` { code(UNIQUE), name, requiresCertification, active, sortOrder }
- **레포**: `DisciplineJpaRepo`
- **seed**: `DisciplineSeeder`(ApplicationRunner) — 부팅 시 기본 종목 idempotent 삽입
- **dto**: `DisciplineResponse`

보안 매처 `GET /disciplines` permitAll 은 `global/security/SecurityConfiguration`.

## 결정 히스토리 (왜 이렇게 됐나)

- **종목 = BE 테이블 (Sanity 아님)** — 자격증 카탈로그는 Sanity로 뺐지만 종목은 BE. 이유: (1) `requiresCertification` 이 강사 신청 자격증 필수 여부를 **BE가 강제**하는 비즈룰, (2) 강의/강사 필터·카운트 등 BE 쿼리 대상, (3) 작고 안정적이라 "배포 없이 추가"의 이점이 약함. enum 도 아님(종목 추가 시 배포 회피). (사용자 결정 2026-06-10)
- **단체(organization)는 종목에 종속하되 Sanity가 관리** — 종목별 단체 목록(프리다이빙→AIDA/SSI/Molchanovs, 스쿠버→PADI/NAUI...)은 **Sanity 카탈로그를 disciplineCode로 키잉**. `Discipline.code` 가 join key. BE는 단체를 검증 안 함(자격증=Sanity 결정 일관).
- **확장(레벨/등급)** — "level2까지 교육 가능" 같은 세분화는 미래. 그땐 자격증(`ApplicationCertificate`)에 `ratingCode` 추가 + Sanity 카탈로그에 종목→단체→레벨 한 층 + 강의 생성 시 강사 레벨 게이트. 지금 구조(문자열 code + 자격증 N건)는 그 확장을 additive로 수용. MVP는 종목별 강사로만.

## 안전망 테스트

`src/test/.../usecase/DisciplineUseCaseTest` (D1 공개목록 / D2 자격증필요플래그 / D3 seed idempotent).
