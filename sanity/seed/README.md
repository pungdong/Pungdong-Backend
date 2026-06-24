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

## venues-extra.ndjson — 전국 공식 다이빙풀 15곳 (자료조사 기반)

`venues.ndjson`(수도권 4곳)에 더해 **전국 실존 3m+ 다이빙풀 15곳**을 자료조사(다중 소스 교차검증)로 추가 적재.
존재성·도로명주소는 정부/공공기관·운영사 1차 출처 기준, 위경도는 지도 플레이스/지오코딩, 입장료·운영시간은
공개 확인분 우선(미공개분은 plausible 값 표기). 안정 `_id`(대시) 라 `--replace` 재실행 멱등.

| _id | 장소 | type | 최대수심 | 지역 | 좌표신뢰도 | 사진 |
|---|---|---|---|---|---|---|
| venue-busan-sajik | 부산 사직 다이빙풀 | DIVING_POOL | 5m | 부산 연제구 | exact | — |
| venue-busan-bukhang | 부산 북항 마리나 다이빙풀 | DEEP_POOL | 24m | 부산 중구 | approx | 3 |
| venue-changwon | 창원실내수영장 다이빙풀 | DIVING_POOL | 5m | 창원 성산구 | exact | 3 |
| venue-songdo | 인천 송도스포츠파크 잠수풀 | DIVING_POOL | 5m | 인천 연수구 | exact | 3 |
| venue-sujak-goyang | 고양 수작코리아 다이빙풀 | DIVING_POOL | 7m | 고양 덕양구 | approx | 3 |
| venue-paradive35 | 시흥 파라다이브35 | DEEP_POOL | 35m | 시흥 | exact | 3 |
| venue-alps-daejeon | 대전 알프스 다이빙센터 | DEEP_POOL | 15m | 대전 중구 | approx | 3 |
| venue-duryu-daegu | 두류수영장 다이빙풀 | DIVING_POOL | 5m | 대구 달서구 | approx | 1 |
| venue-nambu-gwangju | 남부대 시립국제수영장 다이빙풀 | DIVING_POOL | 5m | 광주 광산구 | exact | 3 |
| venue-yeomju-gwangju | 염주체육관 다이빙풀 | DIVING_POOL | 5m | 광주 서구 | approx | — |
| venue-wansan-jeonju | 완산수영장 다이빙풀 | DIVING_POOL | 5m | 전주 완산구 | exact | — |
| venue-tsn-osan | 테마 다이빙풀 (TSN 오산) | DEEP_POOL | 11m | 오산 | exact | 3 |
| venue-newseoul-gwangmyeong | 뉴서울다이빙풀 | DIVING_POOL | 5m | 광명 | exact | 3 |
| venue-divelife-seoul | 다이브라이프 다이빙풀 | DIVING_POOL | 3m | 서울 서초 | approx | 3 |
| venue-mer-goyang | 메르 프리다이빙 센터 | DIVING_POOL | 5m | 고양 일산동구 | exact | 3 |

생성/사진 파이프라인:
- `_gen-extra.mjs` — 조사 데이터를 `schema/venue.ts` 모양으로 직렬화하는 생성기. 사진은 `images/<id>/` 의 파일을
  `_sanityAsset: "image@file://./images/<id>/<file>"` 로 참조(import 시 자동 업로드, 별도 write 토큰 불필요).
- `download-images.sh` — 공개 사진을 `images/<id>/` 로 받아 content-type 검증. (대용량은 2048px 로 다운스케일.)

> ⚠️ **사진 저작권**: `images/` 는 공식 사이트·공공기관·관광공사 CDN·뉴스에서 받은 **seed/placeholder 성격**.
> 실서비스 공개 전 권리 확보된 이미지로 교체 권장. (사용자 결정 2026-06-19.)
>
> **좌표 approx 4곳**(북항 마리나·수작코리아·알프스·염주·… 참고) 은 동/도로 수준 근사 — 핀 정밀도가 필요하면
> 네이버/카카오 플레이스 또는 BE `/geocode`(juso 승인키) 로 재확인. **창원**은 2026 리노베이션 휴장 가능성 있어
> 운영 재확인 필요. 상세 caveat 는 자료조사 산출물 참조.

### 적재

```bash
cd sanity
node seed/_gen-extra.mjs                                            # (사진 추가/수정 시 ndjson 재생성)
npx sanity dataset import seed/venues-extra.ndjson production       # --replace 로 재실행 멱등
```

(로그인 필요 — `npx sanity login`. 이미지는 import 가 `images/` 에서 자동 업로드.)

## ocean-tours.ndjson — 해양(OCEAN) 다이빙 투어 3곳

날씨/해상 데이터 활용 예시(공모전 데모)로 **실제 바다 다이빙 투어** 3곳을 `type=OCEAN` venue 로 추가.
`latitude`/`longitude` 는 **실제 다이브 해역의 좌표**(날씨·해상 데이터 기준점), `address` 는 출항 포구.
tickets 는 1일 보트 펀다이빙 상품. `equipInfo` 에 시즌·수온·**기상 의존성**(투어 취소 조건) 명시.

| _id | 투어 | maxDepth | 출항 | 좌표 | 비고 |
|---|---|---|---|---|---|
| venue-jeju-seogwipo | 제주 서귀포 문섬·범섬 보트 펀다이빙 | 30m | 서귀포항 | exact | 한국 대표 스쿠버 메카, 연산호 군락 |
| venue-ulleungdo | 울릉도 보트 펀다이빙 | 45m | 현포항 | approx | 동해 청정 시야, 쿠로시오 난류 |
| venue-dokdo | 독도 펀다이빙 투어 (울릉도 연계) | 19m | 저동항 | approx | 단독 상설 상품 제한적 → 울릉도 연계, 기상 의존 극심 |

> **설계 메모**: 도메인상 강사 커스텀 다이브포인트는 **BE DB(CUSTOM)**, 투어 상품화는 후속
> ([../docs/features/venue.md](../docs/features/venue.md) 30·93). 여기 3곳은 **공개 유명 포인트**라 데모용으로
> OFFICIAL OCEAN venue(Sanity)로 적재. production 에선 강사 커스텀 포인트=BE 라는 분담을 유지.
>
> 사진: 제주는 Flickr + Wikimedia, 울릉도·독도는 Wikimedia Commons(CC) — 풀 사진보다 라이선스 명확하나
> 실서비스 전 라이선스 표기/교체 확인 권장. 독도 좌표는 동도·서도 사이 해역 근사값.

### 적재

```bash
cd sanity
node seed/_gen-tours.mjs                                            # (사진 추가/수정 시 재생성)
npx sanity dataset import seed/ocean-tours.ndjson production        # --replace 로 멱등
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
