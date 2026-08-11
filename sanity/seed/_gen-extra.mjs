// venues-extra.ndjson 생성기. 조사 결과(deep-research + enrich + 2026-08-11 Track B 전면 재검증
// + 2026-08-11 Chrome 2차 확인 패스 round-2)를
// schema/venue.ts 모양으로 직렬화. 실행: cd sanity/seed && node _gen-extra.mjs
// 사진은 _sanityAsset(file://./images/...) 로 걸어 import 시 자동 업로드.
// ⚠️ images/ (다운로드 산출물, 비커밋) 가 없는 환경에서 재생성해도 기존 ndjson 의 photos 참조를
//    잃지 않도록, 로컬 파일이 없으면 기존 venues-extra.ndjson 의 photos 를 그대로 재사용한다.
import {readdirSync, existsSync, readFileSync, writeFileSync} from 'node:fs'

const FD = 'FREEDIVING', SC = 'SCUBA', MM = 'MERMAID'

// 시간대(부) 블록
const blk = (k, s, e) => ({_key: k, _type: 'venueTimeBlock', start: s, end: e})
// 하루 파트 헬퍼 (fee 미공개면 undefined → JSON 직렬화에서 필드 생략)
const open = (fee, o, c, holdHours) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'OPEN', open: o, close: c, ...(holdHours ? {holdHours} : {})})
const fixed = (fee, blocks) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'FIXED', blocks})
const same = (fee) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'SAME'})
const ticket = (key, name, disciplines, weekday, weekend) => ({_key: key, _type: 'venueTicket', name, disciplines, weekday, ...(weekend ? {weekend} : {})})
// 기본 대여 장비 1종 (venueEquipDefault). sizeFormat 생략 시 FE 가 장비명으로 추론.
const equip = (key, name, price, sizeFormat) => ({_key: key, _type: 'venueEquipDefault', name, price, ...(sizeFormat ? {sizeFormat} : {})})

// 기존 ndjson 의 photos 백업(로컬 images/ 부재 시 재사용)
const prevPhotos = {}
if (existsSync('venues-extra.ndjson')) {
  for (const line of readFileSync('venues-extra.ndjson', 'utf8').split('\n')) {
    if (!line.trim()) continue
    const d = JSON.parse(line)
    prevPhotos[d._id] = d.photos || []
  }
}

// 사진: seed/images/<id>/ 의 파일을 _sanityAsset 참조로 (없으면 기존 참조 유지)
function photos(id) {
  const dir = `images/${id}`
  if (!existsSync(dir)) return prevPhotos[id] || []
  return readdirSync(dir).filter((f) => /\.(jpe?g|png|webp)$/i.test(f)).sort().map((f, i) => ({
    _key: `${id}-p${i + 1}`,
    _type: 'image',
    _sanityAsset: `image@file://./images/${id}/${f}`,
  }))
}

const V = []
const push = (v) => V.push({_type: 'venue', active: true, ...v, photos: photos(v._id)})

// ───────────────────────── 기존 15곳 (2026-08-11 Track B 재검증 반영) ─────────────────────────

// 1. 부산 사직 다이빙풀 — 공공 잠수풀, 회차 예약제. 장비 대여 자체가 없음(공식 명시).
push({
  _id: 'venue-busan-sajik', name: '부산 사직 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '부산광역시 연제구 월드컵대로 344', addressDetail: '사직실내수영장 다이빙풀 (25m×25m)',
  latitude: 35.192943, longitude: 129.064481, sortOrder: 10,
  equipInfo: '부산시 체육시설관리사업소 운영 공공 잠수풀\n물품 대여업체 없음 — 장비 일체 개인 지참(공식 명시)\n회차당 정원 40명 · 이메일 사전예약(sajikswim@korea.kr)\n무자격자는 강사 동반, 유자격자 2인 이상 동반 입장\n문의 051-500-2356',
  closures: [{_key: 'bs-c1', _type: 'venueClosure', type: 'WEEKLY', weekdays: ['MONDAY']}],
  tickets: [
    ticket('bs-t1', '스쿠버다이빙', [SC], fixed(12000, [blk('bs-w1', '07:00', '10:00'), blk('bs-w2', '13:00', '16:00')]), fixed(12000, [blk('bs-e1', '07:00', '10:00'), blk('bs-e2', '12:00', '15:00')])),
    ticket('bs-t2', '프리·하이다이빙', [FD], fixed(8000, [blk('bs-w3', '07:00', '10:00'), blk('bs-w4', '13:00', '16:00')]), fixed(8000, [blk('bs-e3', '07:00', '10:00'), blk('bs-e4', '12:00', '15:00')])),
  ],
})

// 2. 부산 북항 마리나 다이빙풀 — 24m 딥풀. 2026-08 공식 요금표 반영(구간 요금 + 고정 2회차제).
push({
  _id: 'venue-busan-bukhang', name: '부산 북항 마리나 다이빙풀', type: 'DEEP_POOL', maxDepth: 24,
  address: '부산광역시 중구 이순신대로 72', addressDetail: '부산북항마리나 1층 딥다이빙풀 (1.3/3/5/10/24m)',
  latitude: 35.1055, longitude: 129.0455, sortOrder: 11,
  equipInfo: '부산항만공사(BPA) 운영 · 2023.12 개장 · 5단계 수심 1.3·3·5·10·24m (영남권 최고 수심)\n고정 2회차제 — 오전 입장 09:20·출수 11:50·퇴장 12:30 / 오후 입장 13:50·출수 16:20·퇴장 17:00\n공기통 최초 1개 무료 · 그 외 장비 대여 미공개(현장 문의) · 정원 42명\n체험다이빙 1인 120,000원(2인 이상 인당 100,000원)\n환불: 이용 2일 전 17시까지 전액, 1일 전 자정까지 10% 위약금, 당일 불가',
  defaultEquipment: [equip('bb-e1', '공기통(추가)', 10000, 'NONE')],
  closures: [],
  tickets: [
    ticket('bb-t1', '3시간권 (회차제)', [FD, SC], fixed(33000, [blk('bb-w1', '09:20', '12:30'), blk('bb-w2', '13:50', '17:00')]), fixed(55000, [blk('bb-e2', '09:20', '12:30'), blk('bb-e3', '13:50', '17:00')])),
    ticket('bb-t2', '6시간권 (평일 전용)', [FD, SC], fixed(53000, [blk('bb-w3', '09:20', '12:30'), blk('bb-w4', '13:50', '17:00')])),
  ],
})

// 3. 창원실내수영장 다이빙풀 — ⚠️ 2026-04-01~12-31 전 시설 휴장(공식 공지) → active:false (사용자 확인 대상)
push({
  _id: 'venue-changwon', name: '창원실내수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5, active: false,
  address: '경상남도 창원시 성산구 원이대로 450', addressDetail: '창원실내수영장 다이빙풀 (25m×25m)',
  latitude: 35.2319799, longitude: 128.6697102, sortOrder: 12,
  equipInfo: '⚠️ 2026-04-01~12-31 전 시설 휴장 — 전국 수영대회 개최·대규모 시설개선 공사(창원시설공단 공지 2026-02-06). 재개장 미정(2027 이후 예상)\n창원시설공단 운영 · 정상 운영 시 1일 4시간 이내 이용 제한\n슈트·후드 착용 의무 · 유자격 2인 이상 동반 또는 강사 인솔\n장비 대여 업체 상주(품목·가격 미공개 — 현장 문의)\n문의 055-712-0661',
  closures: [
    {_key: 'cw-c1', _type: 'venueClosure', type: 'MONTHLY', nth: 2, monthlyWeekday: 'SUNDAY'},
    {_key: 'cw-c2', _type: 'venueClosure', type: 'MONTHLY', nth: 4, monthlyWeekday: 'SUNDAY'},
  ],
  tickets: [
    ticket('cw-t1', '개인 이용권', [FD, SC], fixed(10000, [blk('cw-w1', '06:00', '12:00'), blk('cw-w2', '13:00', '20:30')]), fixed(10000, [blk('cw-e1', '06:00', '12:00'), blk('cw-e2', '13:00', '17:30')])),
  ],
})

// 4. 인천 송도스포츠파크 잠수풀 — 공공, 라이센스 필수. 3부제·월요휴무 반영(2차 출처 — 재확인 권장).
push({
  _id: 'venue-songdo', name: '인천 송도스포츠파크 잠수풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '인천광역시 연수구 인천신항대로892번길 40', addressDetail: '송도스포츠파크 잠수풀 (25m×11m)',
  latitude: 37.3513500430139, longitude: 126.618545944987, sortOrder: 13,
  equipInfo: '인천환경공단 운영 · 3부제(각 부 마감 30분 전 퇴수)\n라이센스 필수 · 예약제 · 개별 장비 대여 미공개(개인 장비 지참 전제)\n환불: 사용일 전날까지 100%, 당일 불가\n문의 032-899-4875\n※ 월요일 휴무·부제 시간대는 2차 출처 — 공식 재확인 권장',
  defaultEquipment: [equip('sd-e1', '공기탱크 충전·대여', 8000, 'NONE')],
  closures: [{_key: 'sd-c1', _type: 'venueClosure', type: 'WEEKLY', weekdays: ['MONDAY']}],
  tickets: [
    ticket('sd-t1', '잠수풀 이용권', [FD, SC], fixed(5000, [blk('sd-w1', '09:00', '12:00'), blk('sd-w2', '13:00', '16:00'), blk('sd-w3', '18:00', '21:00')]), same(5000)),
  ],
})

// 5. 고양 수작코리아 다이빙풀(일산풀) — 실내수중스튜디오 7m. 기본 10~17시 + 화·수만 야간 연장(모델 한계 → 비고).
push({
  _id: 'venue-sujak-goyang', name: '고양 수작코리아 다이빙풀', type: 'DIVING_POOL', maxDepth: 7,
  address: '경기도 고양시 덕양구 동헌로235번길 120-57', addressDetail: '수작코리아 실내수중스튜디오 (4m·7m 구간)',
  latitude: 37.7095, longitude: 126.9015, sortOrder: 14,
  equipInfo: "수온 연중 약 29도 · '일산풀' 통칭\n기본 10:00~17:00 · 화·수요일만 야간 연장 18:00~22:00\n스킨 풀렌탈 10,000원 · 스쿠버 풀렌탈 30,000원 · 공기통 10,000원 (개별 품목가 미공개 — 공식 확인 권장)\n문의 031-922-6725",
  defaultEquipment: [
    equip('sj-e1', '스킨 장비 풀렌탈', 10000),
    equip('sj-e2', '스쿠버 장비 풀렌탈', 30000),
    equip('sj-e3', '공기통', 10000, 'NONE'),
  ],
  closures: [],
  tickets: [
    ticket('sj-t1', '일반권', [FD, SC], open(20000, '10:00', '17:00'), same(20000)),
  ],
})

// 6. 시흥 파라다이브35 — 35m 딥풀. Chrome 2차(공식+bgrdive+블로그 일치): 기본 장비 무료 + 유료 전표 확정,
//    운영 08~23시 3시간×5부제 확인(나무위키 09~20은 구정보).
push({
  _id: 'venue-paradive35', name: '시흥 파라다이브35', type: 'DEEP_POOL', maxDepth: 35,
  address: '경기도 시흥시 거북섬중앙로 1', addressDetail: '보니타가 1동 3층 · 5단계 1.3/5/10/20/35m',
  latitude: 37.326792, longitude: 126.6839026, sortOrder: 15,
  equipInfo: '2023.10 개장 · 국내 최심급(35m) · 운영 08:00~23:00 3시간×5부제\n기본 장비(마스크·스노클·핀·슈트·웨이트) 무료 대여\n유료: 카본핀 10,000 · 머메이드핀 5,000 · 다이브컴퓨터 10,000 · 공기통 추가 10,000 — 유상 장비 정회원 50% 할인(카본핀 제외)\n평일 6시간권 79,000원 별도 판매\n준회원/정회원 멤버십 할인 별도\n문의 031-497-3133',
  defaultEquipment: [
    equip('pd-e1', '마스크·스노클', 0, 'NONE'),
    equip('pd-e2', '핀', 0, 'SHOE_MM'),
    equip('pd-e3', '슈트', 0, 'APPAREL_SXL'),
    equip('pd-e4', '웨이트', 0, 'NONE'),
    equip('pd-e5', '카본핀', 10000, 'SHOE_MM'),
    equip('pd-e6', '머메이드핀', 5000, 'SHOE_MM'),
    equip('pd-e7', '다이브컴퓨터', 10000, 'NONE'),
    equip('pd-e8', '공기통(추가)', 10000, 'NONE'),
  ],
  closures: [],
  tickets: [
    ticket('pd-t1', '3시간권', [FD, SC, MM], open(45000, '08:00', '23:00'), same(67000)),
  ],
})

// 7. 대전 알프스 다이빙센터 — 15m 딥풀. 2026-08 공식 요금표 반영(주말 45,000·하프권), 렌탈 전표 확보.
push({
  _id: 'venue-alps-daejeon', name: '대전 알프스 다이빙센터', type: 'DEEP_POOL', maxDepth: 15,
  address: '대전광역시 중구 대둔산로 253', addressDetail: '안영동 · 계단식 1.3/6/15m (10m×20m)',
  latitude: 36.2738416, longitude: 127.376925, sortOrder: 16,
  equipInfo: '2024.2 개장 · 중부권 최대 규모(15m) · 수온 약 30도 · 요금은 3시간 기준(초과 15분당 5,000원)\n기본 장비(마스크·스노클·웨이트)+공기통 1통 무료 · 슈트 대여 미공개(현장 문의)\n체험다이빙 80,000원(2시간, 입장료 별도)\n문의 042-585-3440\n※ 좌표 재지오코딩 권장(도로 중심 대비 ~1km 오차 의심)',
  defaultEquipment: [
    equip('al-e1', '마스크', 0, 'NONE'),
    equip('al-e2', '스노클', 0, 'NONE'),
    equip('al-e3', '웨이트벨트', 0, 'NONE'),
    equip('al-e4', '미들핀(기본핀)', 0, 'SHOE_MM'),
    equip('al-e5', '카본핀', 15000, 'SHOE_MM'),
    equip('al-e6', '머메이드핀', 10000, 'SHOE_MM'),
    equip('al-e7', '공기통(추가)', 10000, 'NONE'),
    equip('al-e8', '더블탱크', 15000, 'NONE'),
    equip('al-e9', '더블탱크 세트', 30000, 'NONE'),
  ],
  closures: [],
  tickets: [
    ticket('al-t1', '이용권', [FD, SC], open(35000, '08:00', '23:00'), same(45000)),
    ticket('al-t2', '하프권 (2섹션·평일 전용)', [FD, SC], open(55000, '08:00', '23:00')),
  ],
})

// 8. 두류수영장 다이빙풀(대구) — ⚠️ 2025-12-04부터 천장 안전 문제 전면 휴장 → active:false (사용자 확인 대상)
push({
  _id: 'venue-duryu-daegu', name: '두류수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5, active: false,
  address: '대구광역시 달서구 공원순환로 237', addressDetail: '두류공원 내 두류수영장 다이빙풀 (25m×25m)',
  latitude: 35.8504, longitude: 128.5577, sortOrder: 17,
  equipInfo: '⚠️ 2025-12-04부터 천장 안전 문제로 전면 휴장 — 2026-01 경영연습풀 일부 프로그램만 부분 재개, 다이빙풀 재개 공지 없음(2026-08 확인). 복구 예상 2026-12(연장 가능)\n대구공공시설관리공단 운영 · 두류공원 내\n방문 전 확인 053-623-2156',
  closures: [],
  tickets: [
    ticket('du-t1', '일반권', [FD, SC], open(12000, '09:00', '20:00'), same(12000)),
  ],
})

// 9. 남부대학교시립국제수영장 다이빙풀(광주) — 공공, 2015 개장. 렌탈 전표(공식)·휴무 반영.
push({
  _id: 'venue-nambu-gwangju', name: '남부대 시립국제수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '광주광역시 광산구 남부대길 25', addressDetail: '다이빙풀 35m×25m×5m',
  latitude: 35.2073991, longitude: 126.8413661, sortOrder: 18,
  equipInfo: '광주광역시 시립 · 2015.5 개장 · 요금·렌탈은 3시간 기준\n점심시간 12:30~13:30 전원 출수 · 성수기(5~9월) 금·토·일 스쿠버 예약제(문자 010-5432-0323)\n문의 062-460-2015~8 (프리다이빙 062-460-2028)',
  defaultEquipment: [
    equip('nb-e1', '스쿠버 풀세트', 25000, 'NONE'),
    equip('nb-e2', '호흡기', 7000, 'NONE'),
    equip('nb-e3', '부력조절기(BCD)', 7000, 'NONE'),
    equip('nb-e4', '슈트', 5000, 'APPAREL_SXL'),
    equip('nb-e5', '핀', 3000, 'SHOE_MM'),
    equip('nb-e6', '마스크', 3000, 'NONE'),
    equip('nb-e7', '스노클', 2000, 'NONE'),
    equip('nb-e8', '공기통(추가)', 10000, 'NONE'),
  ],
  closures: [
    {_key: 'nb-c1', _type: 'venueClosure', type: 'MONTHLY', nth: 1, monthlyWeekday: 'SUNDAY'},
    {_key: 'nb-c2', _type: 'venueClosure', type: 'MONTHLY', nth: 3, monthlyWeekday: 'SUNDAY'},
  ],
  tickets: [
    ticket('nb-t1', '프리다이빙', [FD], open(15000, '09:00', '21:00'), open(15000, '09:00', '17:00')),
    ticket('nb-t2', '스쿠버다이빙(공기통 1개 포함)', [SC], open(21000, '09:00', '21:00'), open(21000, '09:00', '17:00')),
  ],
})

// 10. 염주체육관 다이빙풀(광주) — 연락처 062-269-8484 확정(갭필). 장비 미공개.
push({
  _id: 'venue-yeomju-gwangju', name: '염주체육관 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '광주광역시 서구 금화로 278', addressDetail: '염주종합체육관 실내 다이빙풀 (25m×25m, 3~5m)',
  latitude: 35.135278, longitude: 126.878889, sortOrder: 19,
  equipInfo: '스쿠버·프리다이빙·생존수영·인명구조\n토요일은 13시 개장 · 장비 대여 미공개(카카오채널·현장 문의)\n문의 062-269-8484\n※ 좌표 재지오코딩 권장(미검증 — 도로명주소는 확정)',
  closures: [],
  tickets: [
    ticket('yj-t1', '일반권', [FD, SC], open(12000, '09:00', '20:30'), open(12000, '09:00', '18:00')),
  ],
})

// 11. 완산수영장 다이빙풀(전주) — 공공. 운영시간 정정(수영장 전체 기준일 수 있음 — 비고).
push({
  _id: 'venue-wansan-jeonju', name: '완산수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '전북특별자치도 전주시 완산구 쑥고개로 366-7', addressDetail: '완산수영장 다이빙풀 (수심 3·5m)',
  latitude: 35.8025818, longitude: 127.1062461, sortOrder: 20,
  equipInfo: '전주시설관리공단 운영 · 2004.3 개관\n일·공휴일은 10시 개장 · 장비 대여 미공개(현장 문의)\n문의 063-239-2580\n※ 운영시간은 수영장 전체 기준일 수 있음 — 다이빙풀 전용 시간 공식 재확인 권장',
  closures: [],
  tickets: [
    ticket('ws-t1', '일반권', [FD, SC], open(10000, '06:00', '20:00'), open(10000, '06:00', '17:00')),
  ],
})

// 12. 테마 다이빙풀(TSN 오산) — 11m 딥풀. 입장료에 풀세트 포함 구조(확인).
push({
  _id: 'venue-tsn-osan', name: '테마 다이빙풀 (TSN 오산)', type: 'DEEP_POOL', maxDepth: 11,
  address: '경기도 오산시 청학로 286', addressDetail: 'TSN 다이빙풀 · 단계 1.5/5/11m',
  latitude: 37.1739566, longitude: 127.0601291, sortOrder: 21,
  equipInfo: '수온 약 29도 · 입장료에 장비 풀세트(BCD·호흡기·슈트·핀 등) 포함\n시간 초과: 평일 30분당 5,000원 / 주말 10,000원\n문의 1660-0677',
  defaultEquipment: [
    equip('ts-e1', '장비 풀세트(입장료 포함)', 0),
    equip('ts-e2', '공기통(추가)', 10000, 'NONE'),
    equip('ts-e3', '더블탱크·사이드마운트 세트', 20000, 'NONE'),
  ],
  closures: [],
  tickets: [
    ticket('ts-t1', '4시간권', [FD, SC], open(33000, '10:00', '22:00'), same(44000)),
  ],
})

// 13. 뉴서울다이빙풀(광명) — SSI/NAUI 트레이닝. Chrome 2차: 공식 공지 확정(scubapool.com wr_id=181,
//     2026-05-01 기준 요금) — 운영 확인, 종목별 요금·렌탈·운영시간 반영.
push({
  _id: 'venue-newseoul-gwangmyeong', name: '뉴서울다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '경기도 광명시 하안로288번길 15', addressDetail: '조일프라자 지하3층',
  latitude: 37.4622649, longitude: 126.8813414, sortOrder: 22,
  equipInfo: 'SSI·NAUI 인스트럭터 트레이닝 시설 · 요금은 공식 공지 기준(2026-05-01)\n스쿠버는 탱크 16,000원(개당) 별도 · 스쿠버 풀세트 대여 23,000원(개별 대여 가능 — 개별가 미확인)\n자율(풀타임) 이용 · 365일 사전통보제 · 강사등록 연 150,000원(2인 이상 강습 시 입장·탱크 무료)\n문의 02-892-4943',
  defaultEquipment: [
    equip('ns-e1', '공기통', 16000, 'NONE'),
    equip('ns-e2', '스쿠버 풀세트', 23000, 'NONE'),
  ],
  closures: [],
  tickets: [
    ticket('ns-t1', '프리다이빙·입영', [FD], open(19000, '09:00', '21:00'), open(19000, '09:00', '18:00')),
    ticket('ns-t2', '스쿠버다이빙 (탱크 별도)', [SC], open(16000, '09:00', '21:00'), open(16000, '09:00', '18:00')),
  ],
})

// 14. 다이브라이프(서울 서초) — Chrome 2차: 입장 20,000원/2시간(2019~2022 후기 다수 — 공식 페이지 없음),
//     장비 풀세트 11,000원(개별 선택 대여 가능) 반영.
push({
  _id: 'venue-divelife-seoul', name: '다이브라이프 다이빙풀', type: 'DIVING_POOL', maxDepth: 3,
  address: '서울특별시 서초구 반포대로20길 27', addressDetail: '서궁빌딩 지하1층 (3m×5m×8m)',
  latitude: 37.4881255, longitude: 127.0117735, sortOrder: 23,
  equipInfo: '프리다이빙·스쿠버다이빙·인어(머메이드) 강습 자체 풀 · 네이버 예약제(1인 2시간)\n입장료 20,000원/2시간 — 후기 다수(2019~2022) 기반, 공식 미게시(체험 프리다이빙 40,000원~ 상품 별도)\n장비 풀세트 대여 11,000원 · 개별 선택 대여 가능(개별가 미공개)',
  defaultEquipment: [equip('dl-e1', '장비 풀세트', 11000, 'NONE')],
  closures: [],
  tickets: [
    ticket('dl-t1', '일반권 (2시간)', [FD, SC, MM], open(20000, '10:00', '22:00'), same(20000)),
  ],
})

// 15. 메르 프리다이빙 센터(고양) — 해수 잠수풀. 주말 단축 운영 정정, 연중무휴 확인.
push({
  _id: 'venue-mer-goyang', name: '메르 프리다이빙 센터', type: 'DIVING_POOL', maxDepth: 5,
  address: '경기도 고양시 일산동구 애니골길 97', addressDetail: '고양국제청소년문화센터 지하1층 · 해수 잠수풀',
  latitude: 37.676393, longitude: 126.7918297, sortOrder: 24,
  equipInfo: '해수로 채운 잠수풀 + 수영장 · 일산 유일 자체 다이빙풀 · 연중무휴\n수심 표시 1.3·2.5·5m · 입장료 25,000원은 공식 미게시(재확인 필요)\n장비 대여 가능 · 가격 현장 문의(렌탈 공식가 미공개 — Chrome 2차에서도 공식 사이트는 마케팅 페이지뿐)\n문의 031-905-0205',
  closures: [],
  tickets: [
    ticket('mr-t1', '일반권', [FD, SC], open(25000, '09:00', '22:00'), open(25000, '09:00', '18:00')),
  ],
})

// ───────────────────────── 신규 8곳 (2026-08-11 Track B 발굴 — 신뢰도 확실만) ─────────────────────────
// 좌표: 근사값(소수 2자리 수준)이거나 미확인 — 공개 전 도로명주소 기반 재지오코딩 필수(README caveat).

// 16. 강릉 국민체육센터 잠수풀 — 공영. 요금은 수영장 일반 기준(잠수풀 별도 요금 확인 필요).
push({
  _id: 'venue-gangneung-sports', name: '강릉 국민체육센터 잠수풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '강원특별자치도 강릉시 수리골길 76', addressDetail: '국민체육센터 잠수풀 (11m×3.5m×5m)',
  sortOrder: 25,
  equipInfo: '강릉관광개발공사 운영 · 2009년 건립(경영풀 8레인+유아풀+잠수풀 복합)\n입장료 3,500원은 수영장 일반 요금 기준 — 잠수풀 별도 요금·강습 대관 가능 여부 확인 필요\n장비 대여 미확인 · 지원 종목 명시 없음(일반 잠수풀)\n※ 월요일 휴무는 운영표(화~토·일)에서 파생 — 공식 재확인 권장',
  closures: [{_key: 'gn-c1', _type: 'venueClosure', type: 'WEEKLY', weekdays: ['MONDAY']}],
  tickets: [
    ticket('gn-t1', '일반권', [FD, SC], open(3500, '06:00', '22:00'), open(3500, '08:00', '20:00')),
  ],
})

// 17. 대전 용운국제수영장 다이빙풀 — 공공(대전시시설관리공단).
push({
  _id: 'venue-yongun-daejeon', name: '용운국제수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '대전광역시 동구 동부로 138', addressDetail: '용운스포츠센터 다이빙풀 (33m×25m×3~5m)',
  latitude: 36.34, longitude: 127.45, sortOrder: 26,
  equipInfo: '대전광역시시설관리공단 운영\n다이빙풀 전용 시간대는 강좌·대관과 혼재 — 사전 확인 필요\n평일/주말 요금 구분 미확인(12,000원 단일 표기) · 장비 대여 미확인 · 정기휴무 미확인\n문의 042-280-1015',
  closures: [],
  tickets: [
    ticket('yg-t1', '일반권', [FD, SC], open(12000, '09:00', '21:00'), open(12000, '09:00', '17:00')),
  ],
})

// 18. 충북학생수영장 다이빙장(청주) — 교육청 운영. 강사 동반 입장만 가능(정책 특이).
push({
  _id: 'venue-chungbuk-cheongju', name: '충북학생수영장 다이빙장', type: 'DIVING_POOL', maxDepth: 5,
  address: '충청북도 청주시 청원구 공항로59번길 33', addressDetail: '충북학생수영장 다이빙장',
  latitude: 36.65, longitude: 127.44, sortOrder: 27,
  equipInfo: '충청북도교육청 운영\n⚠️ 강사(지도자급 이상) 동반 입장만 가능 — 인솔자 1명당 최대 5명, 유자격자도 단독 입장 불가\n다이빙풀 운영 시간대: 오전 09~12시 / 오후 13~15시(월~토)\n장비 대여 미확인\n문의 043-254-7251',
  closures: [{_key: 'cb-c1', _type: 'venueClosure', type: 'WEEKLY', weekdays: ['SUNDAY']}],
  tickets: [
    ticket('cb-t1', '일반권 (강사 동반)', [FD, SC], fixed(10000, [blk('cb-w1', '09:00', '12:00'), blk('cb-w2', '13:00', '15:00')]), same(10000)),
  ],
})

// 19. 부산 송도해양레포츠센터 다이빙풀 — 공영. 부산 유일 7m 구간.
push({
  _id: 'venue-busan-songdo', name: '송도해양레포츠센터 다이빙풀', type: 'DIVING_POOL', maxDepth: 7,
  address: '부산광역시 서구 송도해변로 50', addressDetail: '송도해양레포츠센터 (구역별 2/5/7m · 7m 구간 24m)',
  latitude: 35.076, longitude: 129.017, sortOrder: 28,
  equipInfo: '부산광역시 공영시설 · 서바이벌수영·프리다이빙·스킨스쿠버\n3부제 상시입장(오전 09:30~12:30 / 오후 14:00~17:00 / 야간 18:30~21:30)\n장비 대여 가능(공기통 등) — 품목·가격 미공개(현장 문의) · 정기휴무 미확인\n문의 051-717-2883',
  closures: [],
  tickets: [
    ticket('bd-t1', '1회차권 (3시간)', [FD, SC], fixed(12000, [blk('bd-w1', '09:30', '12:30'), blk('bd-w2', '14:00', '17:00'), blk('bd-w3', '18:30', '21:30')]), same(12000)),
    ticket('bd-t2', '오전+오후권 (2회차)', [FD, SC], fixed(20000, [blk('bd-w4', '09:30', '12:30'), blk('bd-w5', '14:00', '17:00')]), same(20000)),
  ],
})

// 20. 문수실내수영장 다이빙풀(울산) — 공영. 프리다이빙 강습 활발(스쿠버 여부 미확인 → FREEDIVING 만).
push({
  _id: 'venue-munsu-ulsan', name: '문수실내수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '울산광역시 남구 문수로 44', addressDetail: '문수실내수영장 다이빙풀 (25m×35m×5m)',
  latitude: 35.53, longitude: 129.29, sortOrder: 29,
  equipInfo: '울산광역시체육시설관리공단 운영\n프리다이빙 강습 활발 — 스쿠버 이용 가능 여부 확인 필요\n요금은 수영장 전체 기준일 가능성(다이빙풀 별도 요금표 미확인) · 장비 대여 미확인 · 정기휴무 미확인\n문의 052-220-2214',
  closures: [],
  tickets: [
    ticket('ms-t1', '일반권', [FD], open(15000, '09:00', '21:30'), open(16000, '09:00', '17:30')),
  ],
})

// 21. 군산 오션팔레트 잠수풀 — 2026-07-10 개장(고군산군도 무녀도, 해양레저 복합단지).
push({
  _id: 'venue-gunsan-oceanpalette', name: '군산 오션팔레트 잠수풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '전북특별자치도 군산시 옥도면 무녀도3길 45-64', addressDetail: '오션팔레트 잠수풀 (20m×10m×5m)',
  latitude: 35.96, longitude: 126.4, sortOrder: 30,
  equipInfo: '서해안권 해양레저 복합단지 오션팔레트 내 · 2026-07-10 개장\n17:00 입장 마감 · 스쿠버 체험 1시간 150,000원(풀장비·기본강습·사진 포함)\n자유이용 장비 렌탈 별도 — 품목·가격 미공개\n프리다이빙 지원 여부 미확인 · 정기휴무 미확인',
  closures: [],
  tickets: [
    ticket('gs-t1', '자유이용권', [SC], open(30000, '10:00', '18:00'), same(30000)),
  ],
})

// 22. 울진해양레포츠센터 잠수풀 — 경북 울진(갭필 확인). 입장료 미공개.
//     ※ marinsports.co.kr "마린스포츠센터"는 울진 소재 근거 없음(별개 시설 추정) — 등재하지 않음.
push({
  _id: 'venue-uljin-marine', name: '울진해양레포츠센터 잠수풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '경상북도 울진군 매화면 오산항길 59', addressDetail: '울진해양레포츠센터 잠수풀 (동시 약 80명)',
  sortOrder: 31,
  equipInfo: '한국프리다이빙협회 안내 시설 · 사전예약\n입장료 미공개 — 전화·스마트스토어 문의 · 휴게 12:00~13:00\n장비 대여 미확인 · 정기휴무 미확인\n문의 054-783-6161 / 010-6540-7003\n※ 운영시간은 문화관광 소개 페이지 기준 — 잠수풀 전용 시간 재확인 권장',
  closures: [],
  tickets: [
    ticket('uj-t1', '일반권', [FD, SC], open(undefined, '09:00', '18:00'), same(undefined)),
  ],
})

// 23. 인어다이브 용인다이빙풀 — 갭필로 운영 확인(Threads 2026-07). 입장료에 스킨 장비 포함.
//     Chrome 2차(나무위키 상세): 주소·전화·요금(20,000/3h)·운영시간(평일 09:30~21:30 4부/주말 09:00~21:00 4부)
//     교차 확인 — 기존 반영값과 일치, 비고만 보강.
push({
  _id: 'venue-ina-yongin', name: '인어다이브 용인다이빙풀', type: 'DIVING_POOL', maxDepth: 3,
  address: '경기도 용인시 처인구 포곡읍 둔전로47번길 21', addressDetail: '지하1층 · 3m×20m 레인',
  sortOrder: 32,
  equipInfo: '프리다이빙·머메이드·생존수영·입영·인명구조 교육 전문(스쿠버 지원 미확인)\n입장료 20,000원(3시간)에 마스크·스노클·오리발·슈트 포함 · 네이버 예약 필수\n정기휴무 미확인\n문의 031-321-0250\n※ 주소·전화·요금·운영시간 나무위키 상세와 교차 확인(2026-08-11)',
  defaultEquipment: [
    equip('ia-e1', '마스크·스노클', 0, 'NONE'),
    equip('ia-e2', '핀', 0, 'SHOE_MM'),
    equip('ia-e3', '슈트', 0, 'APPAREL_SXL'),
  ],
  closures: [],
  tickets: [
    ticket('ia-t1', '3시간권 (장비 포함)', [FD, MM],
      fixed(20000, [blk('ia-w1', '09:30', '12:30'), blk('ia-w2', '12:30', '15:30'), blk('ia-w3', '15:30', '18:30'), blk('ia-w4', '18:30', '21:30')]),
      fixed(20000, [blk('ia-e4', '09:00', '12:00'), blk('ia-e5', '12:00', '15:00'), blk('ia-e6', '15:00', '18:00'), blk('ia-e7', '18:00', '21:00')])),
  ],
})

// ───────────────────────── 신규 1곳 (2026-08-11 Chrome 2차 확인 패스 — 누락 발견) ─────────────────────────

// 24. 올림픽수영장 잠수풀(올팍, 송파) — 유명 풀인데 카탈로그·발굴 스윕 모두 누락 → Chrome 2차에서 발견·등재.
//     국민체육진흥공단(KSPO) 운영, 인스타 @odp_divingpool 활발(2026-02 일정 공지 — 운영 중 확정).
//     좌표 미기입 — 도로명주소 기반 재지오코딩 필요(README caveat).
push({
  _id: 'venue-olympicpool-songpa', name: '올림픽수영장 잠수풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '서울특별시 송파구 올림픽로 424', addressDetail: '올림픽공원 내 올림픽수영장 잠수풀 (25m×25m, 5m) · 통칭 올팍',
  sortOrder: 33,
  equipInfo: '국민체육진흥공단(KSPO) 운영 · 인스타 @odp_divingpool 일정 공지 활발\n라이센스 확인 후 입장 — 프리·스쿠버 라이센스 혼용 입장 불가\n공기탱크 평일 18,000원 / 주말 20,000원(주말 차등 — 기본 장비가는 평일 기준)\n※ 이용시간 14~21시는 나무위키(2022-04 주말 재개장) 기준 — 최신 시간 재확인 권장, 입장료는 2026-03~04 최신값 우선',
  defaultEquipment: [equip('op-e1', '공기통', 18000, 'NONE')],
  closures: [{_key: 'op-c1', _type: 'venueClosure', type: 'MONTHLY', nth: 2, monthlyWeekday: 'SUNDAY'}],
  tickets: [
    ticket('op-t1', '프리다이빙', [FD], open(18000, '14:00', '21:00'), open(22000, '14:00', '21:00')),
    ticket('op-t2', '스쿠버다이빙', [SC], open(18000, '14:00', '21:00'), open(20000, '14:00', '21:00')),
  ],
})

const out = V.map((v) => JSON.stringify(v)).join('\n') + '\n'
writeFileSync('venues-extra.ndjson', out)
console.error(`wrote ${V.length} venues, ${V.reduce((n, v) => n + v.photos.length, 0)} photos`)
