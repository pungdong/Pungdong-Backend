// ocean-tours.ndjson 생성기. OCEAN(해양) 다이빙 투어 3곳 — 날씨/해상 데이터 활용 예시(공모전 데모).
// 실행: cd sanity/seed && node _gen-tours.mjs
// 주의: 도메인 설계상 강사 커스텀 다이브포인트는 BE DB(CUSTOM), 투어 상품화는 후속.
//        여기서는 공개 유명 포인트를 OFFICIAL OCEAN venue 로 Sanity 에 데모 적재(docs/features/venue.md 93-94).
// ⚠️ images/ (다운로드 산출물, 비커밋) 가 없는 환경에서 재생성해도 기존 ndjson 의 photos 참조를
//    잃지 않도록, 로컬 파일이 없으면 기존 ocean-tours.ndjson 의 photos 를 그대로 재사용한다.
import {readdirSync, existsSync, readFileSync, writeFileSync} from 'node:fs'

const FD = 'FREEDIVING', SC = 'SCUBA'
const open = (fee, o, c) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'OPEN', open: o, close: c})
const same = (fee) => ({_type: 'venueDaypart', sold: true, fee, timeMode: 'SAME'})
const ticket = (key, name, disciplines, weekday, weekend) => ({_key: key, _type: 'venueTicket', name, disciplines, weekday, ...(weekend ? {weekend} : {})})
const equip = (key, name, price, sizeFormat) => ({_key: key, _type: 'venueEquipDefault', name, price, ...(sizeFormat ? {sizeFormat} : {})})

const prevPhotos = {}
if (existsSync('ocean-tours.ndjson')) {
  for (const line of readFileSync('ocean-tours.ndjson', 'utf8').split('\n')) {
    if (!line.trim()) continue
    const d = JSON.parse(line)
    prevPhotos[d._id] = d.photos || []
  }
}

function photos(id) {
  const dir = `images/${id}`
  if (!existsSync(dir)) return prevPhotos[id] || []
  return readdirSync(dir).filter((f) => /\.(jpe?g|png|webp)$/i.test(f)).sort().map((f, i) => ({
    _key: `${id}-p${i + 1}`, _type: 'image', _sanityAsset: `image@file://./images/${id}/${f}`,
  }))
}

const V = []
const push = (v) => V.push({_type: 'venue', active: true, ...v, photos: photos(v._id)})

// 1. 제주 서귀포 문섬·범섬·섶섬 보트 펀다이빙 — 한국 대표 스쿠버 메카 (2026-08-11 가격·렌탈 재확인 일치)
push({
  _id: 'venue-jeju-seogwipo', name: '제주 서귀포 문섬·범섬 보트 펀다이빙', type: 'OCEAN', maxDepth: 30,
  address: '제주특별자치도 서귀포시 칠십리로72번길 14', addressDetail: '서귀포항 출항 · 문섬·범섬·섶섬 무인도 보트 펀다이빙',
  latitude: 33.226475, longitude: 126.565994, sortOrder: 30,
  equipInfo: '한국 대표 스쿠버 다이빙 메카 · 문섬 연산호 군락(산호정원)\n1일 보트 펀다이빙 2회 + 브리핑 + 수중촬영 (오픈워터 이상)\n무인도 입도 보팅비 별도(최초 1회 15,000원) · 렌탈가는 업체별 상이(아래는 해빛다이브 기준, 2일째부터 1일 10,000원 할인)\n운영 예: 씨플로우/누디다이브/마코다이브/해빛다이브 등\n수온 여름 24~27도·겨울 14~16도(드라이슈트) · 풍랑/너울 시 당일 출항 취소 가능',
  defaultEquipment: [
    equip('jj-e1', '풀세트(랜턴·컴퓨터 제외)', 30000),
    equip('jj-e2', '호흡기', 15000, 'NONE'),
    equip('jj-e3', 'BCD(부력조절기)', 15000, 'NONE'),
    equip('jj-e4', '핀', 5000, 'SHOE_MM'),
    equip('jj-e5', '슈트', 5000, 'APPAREL_SXL'),
    equip('jj-e6', '부츠', 5000, 'SHOE_MM'),
    equip('jj-e7', '마스크', 5000, 'NONE'),
    equip('jj-e8', '후드', 3000, 'NONE'),
    equip('jj-e9', '랜턴', 10000, 'NONE'),
    equip('jj-e10', '다이빙 컴퓨터', 10000, 'NONE'),
  ],
  closures: [],
  tickets: [
    ticket('jj-t1', '1일 보트 펀다이빙 (2회)', [SC, FD], open(120000, '08:00', '17:00'), same(120000)),
  ],
})

// 2. 울릉도 보트 펀다이빙 — 동해 청정 시야, 쌍정초/삼선암/죽도 (가격 재확인 — 검색엔진 경유, 1차 출처 봇차단)
push({
  _id: 'venue-ulleungdo', name: '울릉도 보트 펀다이빙', type: 'OCEAN', maxDepth: 45,
  address: '경상북도 울릉군 북면 현포1길 5', addressDetail: '현포항 출항 · 쌍정초·삼선암·죽도·거북바위 등 포인트',
  latitude: 37.5263596, longitude: 130.8251746, sortOrder: 31,
  equipInfo: '동해 청정 수중 시야(20~50m) · 쌍정초는 울릉 최고 포인트(시야 30~40m)\n1일 보트 펀다이빙 2회 · 추가 다이빙 6만원/회(재확인 필요) · 렌탈가는 업체별 상이(아래는 울릉현포다이브 기준·간접 확인)\n운영 예: 울릉현포다이브/울릉아쿠아캠프/학포다이버리조트\n성수기 6~10월 · 8~10월 쿠로시오 난류로 아열대 회유어 · 외해 화산섬이라 기상 민감(당일 취소·포인트 변경)',
  defaultEquipment: [
    equip('ul-e1', '스쿠버 풀세트', 50000),
  ],
  closures: [],
  tickets: [
    ticket('ul-t1', '1일 보트 펀다이빙 (2회)', [SC, FD], open(130000, '08:00', '17:00'), same(130000)),
  ],
})

// 3. 독도 펀다이빙 투어 — ⚠️ 2026-08-11 조사: 일반 상업 펀다이빙 불가(문화재보호구역·허가제) → active:false
//    (제거 여부는 사용자 결정 대기 — decisions.md / research/existing/v5-honam-ocean.md ⑥)
push({
  _id: 'venue-dokdo', name: '독도 펀다이빙 투어 (울릉도 연계)', type: 'OCEAN', maxDepth: 19, active: false,
  address: '경상북도 울릉군 울릉읍 저동리', addressDetail: '저동항 출항 · 독도 동도·서도 연안 수중 암초군 (기상 허용 시 연계)',
  latitude: 37.2406, longitude: 131.8669, sortOrder: 32,
  equipInfo: '⚠️ 독도는 문화재보호구역 — 일반 관람은 동도 선착장 한정, 관광 외 목적(다이빙 포함) 입도는 문화재청장 사전 허가 필요(14일 전 신청)·학술 목적 위주. 상시 상업 펀다이빙 상품화 불가로 확인(2026-08-11) — 노출 전 법적·운영 리스크 재검토 필요\n독도 동도·서도 연안 암초군(가지초·삼봉초 등) · 삼형제굴바위 랜드마크\n실질은 울릉도 베이스 펀다이빙 + 기상 양호 시 독도 해역 연계(별도 추가요금·사전조율)\n추천 5~10월 · 표층 수온 여름 22~25도 · 외해라 기상 의존 극심(독도 접안 연 60일 미만, 풍랑특보 시 운항 중단)',
  closures: [],
  tickets: [
    ticket('dd-t1', '1일 보트 펀다이빙 (2회, 독도 연계)', [SC], open(130000, '08:00', '17:00'), same(130000)),
  ],
})

const out = V.map((v) => JSON.stringify(v)).join('\n') + '\n'
writeFileSync('ocean-tours.ndjson', out)
console.error(`wrote ${V.length} ocean tours, ${V.reduce((n, v) => n + v.photos.length, 0)} photos`)
