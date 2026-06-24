// venues-extra.ndjson 생성기. 조사 결과(deep-research + enrich 워크플로)를 schema/venue.ts 모양으로 직렬화.
// 실행: node seed/_gen-extra.mjs > seed/venues-extra.ndjson  (sanity 디렉토리에서)
// 사진은 _sanityAsset(file://./images/...) 로 걸어 import 시 자동 업로드.
import {readdirSync, existsSync} from 'node:fs'
import {writeFileSync} from 'node:fs'

const FD = 'FREEDIVING', SC = 'SCUBA', MM = 'MERMAID'

// 시간대(부) 블록
const blk = (k, s, e) => ({_key: k, _type: 'venueTimeBlock', start: s, end: e})
// 하루 파트 헬퍼
const open = (fee, o, c, holdHours) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'OPEN', open: o, close: c, ...(holdHours ? {holdHours} : {})})
const fixed = (fee, blocks) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'FIXED', blocks})
const same = (fee) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'SAME'})
const ticket = (key, name, disciplines, weekday, weekend) => ({_key: key, _type: 'venueTicket', name, disciplines, weekday, ...(weekend ? {weekend} : {})})

// 사진: seed/images/<id>/ 의 파일을 _sanityAsset 참조로
function photos(id) {
  const dir = `images/${id}`
  if (!existsSync(dir)) return []
  return readdirSync(dir).filter((f) => /\.(jpe?g|png|webp)$/i.test(f)).sort().map((f, i) => ({
    _key: `${id}-p${i + 1}`,
    _type: 'image',
    _sanityAsset: `image@file://./images/${id}/${f}`,
  }))
}

const V = []
const push = (v) => V.push({_type: 'venue', active: true, ...v, photos: photos(v._id)})

// 1. 부산 사직 다이빙풀 — 공공 잠수풀, 회차 예약제
push({
  _id: 'venue-busan-sajik', name: '부산 사직 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '부산광역시 연제구 월드컵대로 344', addressDetail: '사직실내수영장 다이빙풀 (25m×25m)',
  latitude: 35.192943, longitude: 129.064481, sortOrder: 10,
  equipInfo: '부산시 체육시설관리사업소 운영 공공 잠수풀\n회차당 정원 40명 · 이메일 사전예약(sajikswim@korea.kr)\n무자격자는 강사 동반, 유자격자 2인 이상 동반 입장\n문의 051-500-2356',
  closures: [],
  tickets: [
    ticket('bs-t1', '스쿠버다이빙', [SC], fixed(12000, [blk('bs-w1', '07:00', '10:00'), blk('bs-w2', '13:00', '16:00')]), fixed(12000, [blk('bs-e1', '07:00', '10:00'), blk('bs-e2', '12:00', '15:00')])),
    ticket('bs-t2', '프리·하이다이빙', [FD], fixed(8000, [blk('bs-w3', '07:00', '10:00'), blk('bs-w4', '13:00', '16:00')]), fixed(8000, [blk('bs-e3', '07:00', '10:00'), blk('bs-e4', '12:00', '15:00')])),
  ],
})

// 2. 부산 북항 마리나 다이빙풀 — 24m 딥풀 (요금 미공개 → plausible)
push({
  _id: 'venue-busan-bukhang', name: '부산 북항 마리나 다이빙풀', type: 'DEEP_POOL', maxDepth: 24,
  address: '부산광역시 중구 이순신대로 72', addressDetail: '부산북항마리나 클럽하우스 5층 딥다이빙풀 (1.3/3/5/10/24m)',
  latitude: 35.1055, longitude: 129.0455, sortOrder: 11,
  equipInfo: '부산항만공사(BPA) 운영 · 2023.12 개장\n5단계 수심 1.3·3·5·10·24m (영남권 최고 수심)\n문의 051-500-2356',
  closures: [],
  tickets: [
    ticket('bb-t1', '딥다이빙 이용권', [FD, SC], open(40000, '09:00', '21:00'), same(60000)),
  ],
})

// 3. 창원실내수영장 다이빙풀 — 공공, 자격 동반 조건
push({
  _id: 'venue-changwon', name: '창원실내수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '경상남도 창원시 성산구 원이대로 450', addressDetail: '창원실내수영장 다이빙풀 (25m×25m)',
  latitude: 35.2319799, longitude: 128.6697102, sortOrder: 12,
  equipInfo: '창원시설공단 운영\n슈트·후드 착용 의무 · 유자격 2인 이상 동반 또는 강사 인솔\n문의 055-712-0661\n※ 2026 리노베이션 휴장 가능 — 운영 재확인 필요',
  closures: [],
  tickets: [
    ticket('cw-t1', '개인 이용권', [FD, SC], open(10000, '06:00', '20:30'), open(10000, '06:00', '17:30')),
  ],
})

// 4. 인천 송도스포츠파크 잠수풀 — 공공, 라이센스 필수
push({
  _id: 'venue-songdo', name: '인천 송도스포츠파크 잠수풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '인천광역시 연수구 인천신항대로892번길 40', addressDetail: '송도스포츠파크 잠수풀 (25m×11m)',
  latitude: 37.3513500430139, longitude: 126.618545944987, sortOrder: 13,
  equipInfo: '인천환경공단 운영\n라이센스 필수 · 예약제\n문의 032-899-4875',
  closures: [],
  tickets: [
    ticket('sd-t1', '잠수풀 이용권', [FD, SC], open(5000, '09:00', '21:00'), same(5000)),
  ],
})

// 5. 고양 수작코리아 다이빙풀(일산풀) — 실내수중스튜디오 7m
push({
  _id: 'venue-sujak-goyang', name: '고양 수작코리아 다이빙풀', type: 'DIVING_POOL', maxDepth: 7,
  address: '경기도 고양시 덕양구 동헌로235번길 120-57', addressDetail: '수작코리아 실내수중스튜디오 (4m·7m 구간)',
  latitude: 37.7095, longitude: 126.9015, sortOrder: 14,
  equipInfo: "수온 연중 약 29도 · '일산풀' 통칭\n슈트 대여 4,000원 · 에어탱크 10,000원\n문의 031-922-6725",
  closures: [],
  tickets: [
    ticket('sj-t1', '일반권', [FD, SC], open(20000, '10:00', '22:00'), same(20000)),
  ],
})

// 6. 시흥 파라다이브35 — 35m 딥풀, 무료 장비대여
push({
  _id: 'venue-paradive35', name: '시흥 파라다이브35', type: 'DEEP_POOL', maxDepth: 35,
  address: '경기도 시흥시 거북섬중앙로 1', addressDetail: '보니타가 1동 3층 · 5단계 1.3/5/10/20/35m',
  latitude: 37.326792, longitude: 126.6839026, sortOrder: 15,
  equipInfo: '2023.10 개장 · 국내 최심급(35m)\n마스크/스노클/핀/웨이트/슈트 무료 대여 (카본핀만 10,000원)\n문의 031-497-3133',
  closures: [],
  tickets: [
    ticket('pd-t1', '3시간권', [FD, SC, MM], open(45000, '08:00', '23:00'), same(67000)),
  ],
})

// 7. 대전 알프스 다이빙센터 — 15m 딥풀 (요금 미공개 → plausible)
push({
  _id: 'venue-alps-daejeon', name: '대전 알프스 다이빙센터', type: 'DEEP_POOL', maxDepth: 15,
  address: '대전광역시 중구 대둔산로 253', addressDetail: '안영동 · 계단식 1.3/6/15m (10m×20m)',
  latitude: 36.2738416, longitude: 127.376925, sortOrder: 16,
  equipInfo: '2024.2 개장 · 중부권 최대 규모(15m)\n수온 약 30도\n문의 042-585-3440',
  closures: [],
  tickets: [
    ticket('al-t1', '이용권', [FD, SC], open(35000, '08:00', '23:00'), same(50000)),
  ],
})

// 8. 두류수영장 다이빙풀(대구) — 공공
push({
  _id: 'venue-duryu-daegu', name: '두류수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '대구광역시 달서구 공원순환로 237', addressDetail: '두류공원 내 두류수영장 다이빙풀 (25m×25m)',
  latitude: 35.8504, longitude: 128.5577, sortOrder: 17,
  equipInfo: '대구공공시설관리공단 운영 · 두류공원 내\n주말 프리다이빙 일일체험 운영\n문의 053-623-2156',
  closures: [],
  tickets: [
    ticket('du-t1', '일반권', [FD, SC], open(12000, '09:00', '20:00'), same(12000)),
  ],
})

// 9. 남부대학교시립국제수영장 다이빙풀(광주) — 공공, 2015 개장
push({
  _id: 'venue-nambu-gwangju', name: '남부대 시립국제수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '광주광역시 광산구 남부대길 25', addressDetail: '다이빙풀 35m×25m×5m',
  latitude: 35.2073991, longitude: 126.8413661, sortOrder: 18,
  equipInfo: '광주광역시 시립 · 2015.5 개장\n문의 062-460-2015~8 (프리다이빙 062-460-2028)',
  closures: [],
  tickets: [
    ticket('nb-t1', '프리다이빙', [FD], open(15000, '09:00', '21:00'), same(15000)),
    ticket('nb-t2', '스쿠버다이빙(공기통 1개 포함)', [SC], open(21000, '09:00', '21:00'), same(21000)),
  ],
})

// 10. 염주체육관 다이빙풀(광주)
push({
  _id: 'venue-yeomju-gwangju', name: '염주체육관 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '광주광역시 서구 금화로 278', addressDetail: '염주종합체육관 실내 다이빙풀 (25m×25m, 3~5m)',
  latitude: 35.135278, longitude: 126.878889, sortOrder: 19,
  equipInfo: '스쿠버·프리다이빙·생존수영·인명구조\n문의 062-269-8484',
  closures: [],
  tickets: [
    ticket('yj-t1', '일반권', [FD, SC], open(12000, '09:00', '20:30'), open(12000, '09:00', '18:00')),
  ],
})

// 11. 완산수영장 다이빙풀(전주) — 공공
push({
  _id: 'venue-wansan-jeonju', name: '완산수영장 다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '전북특별자치도 전주시 완산구 쑥고개로 366-7', addressDetail: '완산수영장 다이빙풀 (수심 3·5m)',
  latitude: 35.8025818, longitude: 127.1062461, sortOrder: 20,
  equipInfo: '전주시설관리공단 운영 · 2004.3 개관\n문의 063-239-2580',
  closures: [],
  tickets: [
    ticket('ws-t1', '일반권', [FD, SC], open(10000, '09:00', '21:00'), same(10000)),
  ],
})

// 12. 테마 다이빙풀(TSN 오산) — 11m 딥풀
push({
  _id: 'venue-tsn-osan', name: '테마 다이빙풀 (TSN 오산)', type: 'DEEP_POOL', maxDepth: 11,
  address: '경기도 오산시 청학로 286', addressDetail: 'TSN 다이빙풀 · 단계 1.5/5/11m',
  latitude: 37.1739566, longitude: 127.0601291, sortOrder: 21,
  equipInfo: '수온 약 29도\n문의 1660-0677',
  closures: [],
  tickets: [
    ticket('ts-t1', '4시간권', [FD, SC], open(33000, '10:00', '22:00'), same(44000)),
  ],
})

// 13. 뉴서울다이빙풀(광명) — SSI/NAUI 트레이닝
push({
  _id: 'venue-newseoul-gwangmyeong', name: '뉴서울다이빙풀', type: 'DIVING_POOL', maxDepth: 5,
  address: '경기도 광명시 하안로288번길 15', addressDetail: '조일프라자 지하3층',
  latitude: 37.4622649, longitude: 126.8813414, sortOrder: 22,
  equipInfo: 'SSI·NAUI 인스트럭터 트레이닝 시설\n문의 02-892-4943',
  closures: [],
  tickets: [
    ticket('ns-t1', '일반권', [FD, SC], open(5000, '10:00', '22:00'), same(5000)),
  ],
})

// 14. 다이브라이프(서울 서초) — 자체 풀, 프리/스쿠버/인어
push({
  _id: 'venue-divelife-seoul', name: '다이브라이프 다이빙풀', type: 'DIVING_POOL', maxDepth: 3,
  address: '서울특별시 서초구 반포대로20길 27', addressDetail: '서궁빌딩 지하1층',
  latitude: 37.4881255, longitude: 127.0117735, sortOrder: 23,
  equipInfo: '프리다이빙·스쿠버다이빙·인어(머메이드) 강습 자체 풀',
  closures: [],
  tickets: [
    ticket('dl-t1', '일반권', [FD, SC, MM], open(25000, '10:00', '22:00'), same(25000)),
  ],
})

// 15. 메르 프리다이빙 센터(고양) — 해수 잠수풀
push({
  _id: 'venue-mer-goyang', name: '메르 프리다이빙 센터', type: 'DIVING_POOL', maxDepth: 5,
  address: '경기도 고양시 일산동구 애니골길 97', addressDetail: '고양국제청소년문화센터 지하1층 · 해수 잠수풀',
  latitude: 37.676393, longitude: 126.7918297, sortOrder: 24,
  equipInfo: '해수로 채운 잠수풀 + 수영장 · 일산 유일 자체 다이빙풀\n수심 표시 1.3·2.5·5m\n문의 031-905-0205',
  closures: [],
  tickets: [
    ticket('mr-t1', '일반권', [FD, SC], open(25000, '10:00', '22:00'), same(25000)),
  ],
})

const out = V.map((v) => JSON.stringify(v)).join('\n') + '\n'
writeFileSync('venues-extra.ndjson', out)
console.error(`wrote ${V.length} venues, ${V.reduce((n, v) => n + v.photos.length, 0)} photos`)
