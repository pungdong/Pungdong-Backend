# seed — Sanity 초기 데이터

Studio 에서 하나씩 손으로 넣기 불편한 카탈로그를 ndjson 으로 일괄 적재한다. 문서는 **안정 `_id`** 라
`--replace` 로 **재실행해도 멱등**(같은 _id 덮어씀, 다른 문서는 안 건드림).

## venues.ndjson — 공식(OFFICIAL) 위치 카탈로그

ssiduckdive.com 공개 정보 기반 잠수풀/딥풀 4곳(`venue`). `name`=장소명, `address`=도로명(좌표 기준),
`addressDetail`=시설 세부, 수온·장비·예약·주차는 `equipInfo`(자유 텍스트)에. `latitude`/`longitude`
는 **address 도메인 geocoding(juso 좌표 API, EPSG:5179→WGS84)으로 채움** — `address` 도로명을 태웠다.

| _id | 장소 | type | 평일/주말 입장료 | 정기휴무 |
|---|---|---|---|---|
| venue-jamsil | 잠실 다이빙풀장 | DIVING_POOL | 15,000 / 20,000 | 없음 |
| venue-seongnam | 성남 아쿠아라인 | DIVING_POOL | 15,000 / 20,000 | 매월 2·4째 일 |
| venue-suwon | 수원 스포츠 아일랜드 | DIVING_POOL | 18,000 / 18,000 | 매월 2째 수 |
| venue-k26 | 가평 K26 (24m) | DEEP_POOL | 33,000 / 53,000 | 매주 일 |

> 용인 딥스테이션은 어드민이 Studio 에서 직접 등록한 상세본(`_id` auto, 4 tickets)이 이미 있어 seed 에서 제외.

시간 모델: 평일 FIXED(부)/OPEN(상시), 주말 FIXED/SAME(평일과 동일·가격만 다름). `disciplines`
는 프리·스쿠버 공용(같은 입장료라 멀티 태그). 스키마/정책은 [../docs/features/venue.md](../docs/features/venue.md).

### 적재

```bash
cd sanity
npx sanity dataset import seed/venues.ndjson production --replace
```

## certifications.ndjson — 자격증 발급 단체 + 등급 카탈로그

8개 단체(`certOrganization`)와 종목별 등급(`certifications[]`):

> 자격 체계 방향: **정규(평탄화 6레벨) + 스페셜티 + 테크니컬**(TDI 등)로 확장 예정. 현재 seed 는 정규만 — 스페셜티/테크니컬은 런칭 후 종목 확장과 함께 채운다. (그래서 `SDI/TDI` 묶음은 폐기하고 `SDI` 만 둠 — TDI 는 테크니컬 트랙으로.)

| code | 종목 | 등급 |
|---|---|---|
| AIDA | 프리다이빙 | AIDA 1~4 · Instructor · Instructor Trainer |
| MOLCHANOVS | 프리다이빙 | Wave 1~4 · Instructor · Instructor Trainer |
| SSI | 프리다이빙 + 스쿠버 | (각 종목 L1~4 · Instructor · Instructor Trainer) |
| PADI | 프리다이빙 + 스쿠버 | (각 종목 L1~4 · Instructor · Course Director) |
| SDI | 스쿠버 | Open Water ~ Divemaster · Instructor · Instructor Trainer |
| NAUI | 스쿠버 | Open Water ~ Divemaster · Instructor · Course Director |
| CMAS | 프리다이빙 + 스쿠버 | 스쿠버 1★~4★·Instructor★·★★/★★★ / 프리 Freediver 1★~3★·Instructor (L4·IT는 국가별 차이로 없음) |
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
