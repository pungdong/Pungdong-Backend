# seed — Sanity 초기 데이터

Studio 에서 하나씩 손으로 넣기 불편한 카탈로그를 ndjson 으로 일괄 적재한다. 문서는 **안정 `_id`** 라
`--replace` 로 **재실행해도 멱등**(같은 _id 덮어씀, 다른 문서는 안 건드림).

## certifications.ndjson — 자격증 발급 단체 + 등급 카탈로그

6개 단체(`certOrganization`)와 종목별 등급(`certifications[]`):

| code | 종목 | 등급 |
|---|---|---|
| AIDA | 프리다이빙 | AIDA 1~4 · Instructor · Instructor Trainer |
| MOLCHANOVS | 프리다이빙 | Wave 1~4 · Instructor · Instructor Trainer |
| SSI | 프리다이빙 + 스쿠버 | (각 종목 L1~4 · Instructor · Instructor Trainer) |
| PADI | 프리다이빙 + 스쿠버 | (각 종목 L1~4 · Instructor · Course Director) |
| SDI | 스쿠버 | Open Water ~ Divemaster · Instructor · Instructor Trainer |
| NAUI | 스쿠버 | Open Water ~ Divemaster · Instructor · Course Director |

평탄화 `level` 은 6종(`LEVEL_1~4 / INSTRUCTOR / INSTRUCTOR_TRAINER`) — Sanity `certOrganization.ts` ·
BE `CertLevel` enum · `types.ts` `CertLevel` 3중 계약(sanity/CLAUDE.md "계약").

### 적재

```bash
cd sanity
npx sanity dataset import seed/certifications.ndjson production --replace
```

(로그인 필요 — `npx sanity login`. projectId `rc448mwo` / dataset `production`.)
