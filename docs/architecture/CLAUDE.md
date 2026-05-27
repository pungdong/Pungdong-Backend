# CLAUDE.md — 도메인 아키텍처 문서

이 디렉토리는 도메인별 아키텍처 문서의 단일 소스. 작업 디렉토리가 이 폴더면 이 파일이 자동 로드되어 좁은 컨텍스트 제공. 전체 프로젝트 컨벤션은 루트 [CLAUDE.md](../../CLAUDE.md) 참고.

> "**왜** 가 아니라 **어떻게**" — 도메인 문서를 *언제* 갱신해야 하는지는 루트 CLAUDE.md 의 "Architectural changes update README + domain docs". 이 파일은 *실제 편집할 때 어떻게 쓸지* 의 가이드.

---

## 작업 단위 = 한 도메인 = 한 파일

`<domain>.md` 1개 파일에 그 도메인의 모든 내용. sub-doc 없음. 별도 도메인은 별도 파일. 보조 엔티티 (예: Lecture 도메인의 Equipment / Location / LectureImage / LectureMark) 는 같이 묶어서 다룸 — 분리하면 컨텍스트 단절.

## 7-섹션 템플릿

신규 도메인 문서 작성 시 다음 순서 + 헤딩. 도메인이 특수하면 추가 섹션 OK (예: notification 의 stateDiagram, lecture 의 ES 동기화 매트릭스).

1. **한 줄 요약** — 이 도메인이 뭘 하나, 어떤 invariant 가 있나. "Baseline" 도메인 (개선 여지 큰 도메인) 은 첫 단락에서 명시
2. **컴포넌트 지도** — Mermaid `flowchart TB` 또는 `LR`. 도메인 내부 + 크로스 도메인 호출 (단방향성 보이게)
3. **흐름 1~N** — Mermaid `sequenceDiagram`. happy path + 주요 분기 (검증 거부 등)
4. **데이터 모델** — Mermaid `erDiagram`. 의도된 / 의도되지 않은 설계 의도 설명
5. **보안 / 권한 매트릭스** — 엔드포인트별 인증 / 역할 / 소유권 검증 표
6. **알려진 설계 간극** — 🔴 / 🟡 / 🟢 심각도 표시. 각 항목에 **해결안 한 줄** 같이 (수정 PR 들어갈 때 베이스)
7. **더 깊게: 테스트로 보기** — `controller/<X>Test`, `service/<X>Test`, 있으면 `usecase/<X>UseCaseTest`. use-case 없으면 추가 권장 시나리오 5~10개 제안

기존 sign-up.md / notification.md / lecture.md / reservation.md / schedule.md / review.md 가 참고 모델.

---

## Mermaid fence 균형 검증 (PR 푸시 전 필수)

GitHub 가 fence 불균형 시 mermaid 블록 통째로 렌더링 안 함. 푸시 전 항상:

```bash
grep -cE '^```mermaid' docs/architecture/<file>.md
grep -cE '^```$'      docs/architecture/<file>.md
```

규칙: mermaid 갯수 `N` 일 때 `^```$` 갯수는 **`N + 2 × (plain 코드 블록 개수)`** 이어야 함. 불균형이면 push 전 수정.

추가 검증 — 모든 fence 의 줄 번호 한 번 훑기:

```bash
grep -nE '^```' docs/architecture/<file>.md
```

각 mermaid 오프닝에 대해 다음 줄 번호의 plain `^```$` 가 짝인지 직관적으로 확인.

---

## 톤 / 스타일

- **한글 본문**. 코드 / 식별자는 영어 그대로.
- **Baseline 마인드** (기획 변경이 예정된 도메인): 첫 단락에 명시 + "알려진 설계 간극" 섹션을 크게. "현재 어떻게 동작하는가" 박아두는 게 목적, 발견된 버그는 캡처만 하고 이번 PR 에선 수정 X.
- **심각도 표시**: 🔴 (출시 전 수정 권장) / 🟡 (출시 후 정리) / 🟢 (검토 사항) 로 우선순위 명확화.
- **cross-link**: 다른 도메인 문서 / 메모리 파일 / use-case 테스트로 포인터 다용. 예: `[reservation.md](reservation.md) 의 #2 와 같은 데이터` 처럼.

---

## 인덱스 갱신

새 문서 추가 / 상태 변경 시 [README.md](README.md) 의 인덱스 표 갱신.

**충돌 주의**: 여러 도메인 doc PR 이 동시에 열려 있으면 인덱스 인접 라인을 모두 건드려서 머지 시 충돌. 다음 패턴이면 GitHub web editor 의 "Resolve conflicts" 로 한 줄 resolve (양쪽 ✅ / 추가된 row 모두 유지):

```
<<<<<<< HEAD
| 강의 (lecture) | [lecture.md](lecture.md) | ✅ ... |
| 예약 (reservation) | _TODO_ | 🔜 |
=======
| 예약 (reservation) | [reservation.md](reservation.md) | ✅ ... |
| 강의 (lecture) | _TODO_ | 🔜 |
>>>>>>> origin/master
```

→ 양쪽 ✅ 만 남기고 마커 제거.

회피 옵션: 인덱스만 별도 PR 에 몰아넣거나, 도메인 PR 직렬화 (한 번에 하나만 머지 후 다음 시작). 출시 임박엔 첫 옵션 (수동 resolve) 가 가장 가벼움.

---

## use-case 테스트 부재 도메인

현재 `usecase/<X>UseCaseTest.java` 가 있는 도메인은 **sign-up + notification + auth (AuthUseCaseTest)** 뿐. 다른 도메인은 부재. 문서 끝에:

- 부재 명시
- "기획 안정화 후 추가 권장" 시나리오 5~10개 제안 (`C1` 정상 / `V*` 검증 / `D*` 중복 / `X*` 취소·실패 / `R*` 권한 / `S*` 동시성 등 시리즈)
- 예시는 [reservation.md](reservation.md) 의 § 더 깊게 섹션 참고

---

## PR 제목 / 커밋 메시지 컨벤션

- PR 제목: `Docs: <domain> 도메인 아키텍처 문서 (...)`
- 커밋 메시지: `Docs:` 프리픽스, 본문에 다이어그램 개수 / 캡처된 baseline 간극 요약 / 인덱스 상태 변화 / 후속 작업 후보 정도 포함
- PR 본문: 요약 / 왜 / 무엇이 바뀜 / 본인이 PR 머지와 별개로 직접 처리할 것 / 다음 — 한국어. 다른 도메인 doc PR 들 참고
