# 데모/PG심사 제거 체크리스트 (DROP after review)

> 이 브랜치(`demo/pg-review-auto-accept`)에는 **공모전 데모 + 토스 PG 심사용 임시 코드/데이터**가 들어있다.
> 심사·데모가 끝나면 아래대로 정리한다. **이 브랜치는 master 에 머지하지 않는다** — 데모 런타임(`com.diving.pungdong.demo`)은
> master 에 없어야 한다. (영구 런칭 인프라 #77 = `seeded` 컬럼·`siteSettings.showSeededCourses` 토글은 이미 master 에 있고 유지.)

## ① 반드시 제거 — throwaway 런타임

| 대상 | 무엇 | 제거 방법 |
|---|---|---|
| `src/main/java/com/diving/pungdong/demo/DemoAutoAcceptScheduler.java` | 신청을 ~2초 뒤 자동수락 (강사 수동수락 대체). **실 UX 아님** — PG 심사 때 결제 플로우만 보이려는 임시 | 파일 삭제 |
| `pungdong.demo.auto-accept` (application.yml) + `DEMO_AUTO_ACCEPT` (.env.example/.env.local/배포 env) | 위 스케줄러 토글 | 라인 제거 |

## ② 선택 제거 — 게이트 off 면 무해 (데모 지원)

| 대상 | 무엇 | 비고 |
|---|---|---|
| `src/main/java/com/diving/pungdong/demo/SeededCourseAvailabilitySeeder.java` | 시드 강의 강사에게 오늘~+8주 가용시간(coverage) 전체 개방 → 시드 강의 신청 가능 | 기존 `AvailabilityCoverage` 모델에 데이터만 넣음(구조 변경 0). 끄면 비활성. 데모 유지 시 둬도 됨 |
| `pungdong.demo.seed-availability` + `DEMO_SEED_AVAILABILITY` | 위 시더 토글 | |
| `com.diving.pungdong.demo` 패키지 자체 | ①②가 다 빠지면 빈 패키지 | 통째로 삭제 |

## ③ 플래그/토글 상태

- 배포(스테이징) env: 심사 중에만 `DEMO_AUTO_ACCEPT=true`, `DEMO_SEED_AVAILABILITY=true`. 심사 후 둘 다 끄기/제거.
- Sanity `siteSettings.showSeededCourses`: **런칭 시 `false`** → 데모(seeded) 코스가 둘러보기/상세에서 숨겨짐(데이터는 보존, #77 동작). 데모 기간엔 `true`.

## ④ 데모 데이터 (DB / Sanity)

- 데모 코스·강사·위치·가용시간은 전부 `demo_*@plop.cool` 소유 + `course.seeded=1` 로 표시됨.
  - **숨기기**: `showSeededCourses=false` (위 ③) — 가장 안전, 데이터 보존.
  - **완전 삭제**: `python3 scripts/demo_seed.py` 의 `wipe_db()` 가 demo_* 소유 행만 지움(실강사 보존). 또는 수동 DELETE.
- Sanity 에 올린 데모 이미지 에셋(`scripts/demo_sanity_assets.json` 의 CDN URL)은 production dataset 에 남음 — 필요 시 Studio 에서 정리.

## ⑤ 툴/시드 (런타임 미연결 — 둬도 무해)

- `scripts/demo_seed.py`, `scripts/upload_demo_to_sanity.py`, `scripts/demo_sanity_assets.json` — 데모 시딩 도구.
- `sanity/seed/ocean-tours.ndjson` 등 데모성 OFFICIAL 시드.

## 심사/데모 켜는 법 (참고)

```bash
# 배포 env 에 토글 ON 후, 시드 강의 생성 → 1회 부팅하면 가용시간 자동 개방
DEMO_AUTO_ACCEPT=true
DEMO_SEED_AVAILABILITY=true
# (로컬) 앱 띄운 상태에서:  python3 scripts/demo_seed.py   → 그 다음 ./scripts/dev.sh 재기동
```
