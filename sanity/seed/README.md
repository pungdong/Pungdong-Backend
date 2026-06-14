# seed — Sanity 초기 데이터

Studio 에서 하나씩 손으로 넣기 불편한 카탈로그를 ndjson 으로 일괄 적재한다. 문서는 **안정 `_id`** 라
`--replace` 로 **재실행해도 멱등**(같은 _id 덮어씀, 다른 문서는 안 건드림).

## certifications.ndjson — 자격증 발급 단체 + 등급 카탈로그

7개 단체(`certOrganization`)와 종목별 등급(`certifications[]`):

| code | 종목 | 등급 |
|---|---|---|
| AIDA | 프리다이빙 | AIDA 1~4 · Instructor · Instructor Trainer |
| MOLCHANOVS | 프리다이빙 | Wave 1~4 · Instructor · Instructor Trainer |
| SSI | 프리다이빙 + 스쿠버 | (각 종목 L1~4 · Instructor · Instructor Trainer) |
| PADI | 프리다이빙 + 스쿠버 | (각 종목 L1~4 · Instructor · Course Director) |
| SDI | 스쿠버 | Open Water ~ Divemaster · Instructor · Instructor Trainer |
| NAUI | 스쿠버 | Open Water ~ Divemaster · Instructor · Course Director |
| **OTHER** | 프리다이빙 + 스쿠버 | **표준 6레벨을 종목 공통명(`레벨 1`·`강사` 등)으로** |

> **OTHER(기타·직접입력) 계약**: 목록에 없는 단체용 폴백. `certificationsByOrgAndDiscipline` 가 표준 6레벨을 `displayName` = 종목 공통 단계명(`레벨 1`~`강사 양성`, `level-labels` 의 label 과 동일)으로 반환한다. → FE 는 OTHER 를 특별 분기 없이 다른 단체와 똑같은 cascade(단체→레벨)로 처리. 단체 고유 명칭은 없고 단계만.

평탄화 `level` 은 6종(`LEVEL_1~4 / INSTRUCTOR / INSTRUCTOR_TRAINER`) — Sanity `certOrganization.ts` ·
BE `CertLevel` enum · `types.ts` `CertLevel` 3중 계약(sanity/CLAUDE.md "계약").

### 적재

```bash
cd sanity
npx sanity dataset import seed/certifications.ndjson production --replace
```

(로그인 필요 — `npx sanity login`. projectId `rc448mwo` / dataset `production`.)

> ⚠️ **`_id` 에 점(`.`) 쓰지 말 것.** Sanity 는 `_id` 의 점을 `drafts.`/`versions.` 예약 네임스페이스로
> 해석해서, `certOrg.aida` 같은 문서를 **public(published) 결과에서 제외**한다 — 인증 CLI 에선 보이지만
> FE(`useCdn:true`, 비인증)에선 안 보여 디버깅이 까다롭다. **대시(`certOrg-aida`) 사용.** (실제로 한 번 밟음.)
