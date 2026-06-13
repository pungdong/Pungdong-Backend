import {defineType, defineField} from 'sanity'

/**
 * 위치(Venue) — 공식(OFFICIAL) 수영장/딥풀. 어드민이 Sanity 에서 authoring 하는 정적 카탈로그.
 * 강사 코스 빌더가 참조하는 공식 위치 풀.
 *
 * 분담(자세히는 docs/features/venue.md):
 * - 여기(Sanity) = "수영장 정보"(이용시간·입장료·정기휴무·사진 같은 정적 카탈로그).
 * - BE = 강사 커스텀 위치(해양/다이브 포인트) + 코스↔위치 연동 + availability + 부킹.
 * - `disciplines` 값은 BE `discipline.code`(FREEDIVING/SCUBA…)와 1:1 soft-ref.
 *
 * 동기화(미래, BE 가 availability/부킹에서 읽을 때): BE 가 read-side `_rev` 대조로 캐시 정합성 유지
 * + 선택 webhook. 상세는 docs/features/venue.md "캐싱·동기화·모니터링 설계".
 *
 * 시간은 "HH:mm" 문자열(예: "08:00"). 권종(일반/하프/종일)은 새 축이 아니라 이용 옵션 카드를 나눠 등록.
 */

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/
const WEEKDAY_LIST = [
  {title: '월', value: 'MONDAY'},
  {title: '화', value: 'TUESDAY'},
  {title: '수', value: 'WEDNESDAY'},
  {title: '목', value: 'THURSDAY'},
  {title: '금', value: 'FRIDAY'},
  {title: '토', value: 'SATURDAY'},
  {title: '일', value: 'SUNDAY'},
]
// BE discipline.code 와 1:1 (DisciplineSeeder). 추가 시 BE seed + 여기 + certOrganization.ts 동기화.
const DISCIPLINE_LIST = [
  {title: '프리다이빙', value: 'FREEDIVING'},
  {title: '스쿠버다이빙', value: 'SCUBA'},
  {title: '머메이드', value: 'MERMAID'},
]
/** 요일 코드 → 한글 (휴무 미리보기 title 용). */
const KO_DAY: Record<string, string> = Object.fromEntries(WEEKDAY_LIST.map((d) => [d.value, d.title]))
/** 종목 코드 → 한글 (이용 옵션 미리보기 title 의 종목 prefix 용). */
const KO_DISC: Record<string, string> = Object.fromEntries(DISCIPLINE_LIST.map((d) => [d.value, d.title]))

/** 고정 시간대(FIXED)의 한 구간 = "부" (예: 08:00–11:00). */
export const venueTimeBlock = defineType({
  name: 'venueTimeBlock',
  title: '시간대(부)',
  type: 'object',
  fields: [
    defineField({name: 'start', title: '시작', type: 'string', validation: (r) => r.required().regex(HHMM, {name: 'HH:mm'})}),
    defineField({name: 'end', title: '끝', type: 'string', validation: (r) => r.required().regex(HHMM, {name: 'HH:mm'})}),
  ],
  preview: {select: {s: 'start', e: 'end'}, prepare: ({s, e}) => ({title: `${s} – ${e}`})},
})

/**
 * 평일/주말 하루 파트. ① 입장료 ② 시간(고정/상시) 축.
 * - 평일: sold=true 고정, timeMode ∈ FIXED|OPEN
 * - 주말: sold=false(주말 불가) 가능, timeMode ∈ SAME(평일과 동일)|FIXED|OPEN
 * - FIXED → blocks 사용 / OPEN → open + close + holdHours(키반납) / SAME → 평일 구성 따름(가격만 다를 수 있음)
 */
export const venueDaypart = defineType({
  name: 'venueDaypart',
  title: '하루 파트',
  type: 'object',
  fields: [
    defineField({
      name: 'sold', title: '판매', type: 'boolean', initialValue: true,
      description: '주말에 운영 안 하면 끔(평일은 항상 켜둠).',
    }),
    defineField({
      name: 'fee', title: '입장료(원)', type: 'number', validation: (r) => r.min(0),
      hidden: ({parent}) => parent?.sold === false,
    }),
    defineField({
      name: 'timeMode', title: '시간 방식', type: 'string',
      options: {list: [
        {title: '고정 시간대', value: 'FIXED'},
        {title: '상시 입장', value: 'OPEN'},
        {title: '평일과 동일 (주말 전용)', value: 'SAME'},
      ]},
      description: 'FIXED=부 리스트 / OPEN=오픈~클로즈+키반납 / SAME=주말이 평일 구성 따름',
      hidden: ({parent}) => parent?.sold === false,
    }),
    // 아래 3종은 timeMode 에 따라서만 노출 (FIXED→블록 / OPEN→오픈·클로즈·키반납 / SAME→없음).
    defineField({
      name: 'blocks', title: '시간대(부)', type: 'array', of: [{type: 'venueTimeBlock'}],
      hidden: ({parent}) => parent?.sold === false || parent?.timeMode !== 'FIXED',
    }),
    defineField({
      name: 'open', title: '오픈', type: 'string', validation: (r) => r.regex(HHMM, {name: 'HH:mm'}),
      hidden: ({parent}) => parent?.sold === false || parent?.timeMode !== 'OPEN',
    }),
    defineField({
      name: 'close', title: '클로즈', type: 'string', validation: (r) => r.regex(HHMM, {name: 'HH:mm'}),
      hidden: ({parent}) => parent?.sold === false || parent?.timeMode !== 'OPEN',
    }),
    defineField({
      name: 'holdHours', title: '키반납(시간)', type: 'number', validation: (r) => r.min(1),
      hidden: ({parent}) => parent?.sold === false || parent?.timeMode !== 'OPEN',
    }),
  ],
})

/** 정기 휴무 1규칙(위치 공통). 매주 + 월간 동시 가능 → 배열. */
export const venueClosure = defineType({
  name: 'venueClosure',
  title: '정기 휴무',
  type: 'object',
  fields: [
    defineField({
      name: 'type', title: '종류', type: 'string',
      options: {list: [{title: '매주', value: 'WEEKLY'}, {title: '매월 N째 주', value: 'MONTHLY'}]},
      validation: (r) => r.required(),
    }),
    defineField({
      name: 'weekdays', title: '요일 — 매주', type: 'array',
      of: [{type: 'string'}], options: {list: WEEKDAY_LIST},
      hidden: ({parent}) => parent?.type !== 'WEEKLY',
    }),
    // 월간은 atomic — "N째 주 X요일" 1건. "2·4주" 나 "2주 화 + 4주 목"은 휴무 항목을 여러 개 추가.
    defineField({
      name: 'nth', title: '몇째 주(1~5) — 매월', type: 'number',
      options: {list: [1, 2, 3, 4, 5]}, validation: (r) => r.min(1).max(5),
      hidden: ({parent}) => parent?.type !== 'MONTHLY',
    }),
    defineField({
      name: 'monthlyWeekday', title: '요일 — 매월', type: 'string', options: {list: WEEKDAY_LIST},
      hidden: ({parent}) => parent?.type !== 'MONTHLY',
    }),
  ],
  preview: {
    select: {type: 'type', weekdays: 'weekdays', nth: 'nth', mw: 'monthlyWeekday'},
    prepare: ({type, weekdays, nth, mw}) => {
      if (type === 'WEEKLY') {
        const days = (weekdays || []).map((d: string) => KO_DAY[d] || d).join('·')
        return {title: days ? `매주 ${days}요일` : '매주 (요일 미정)'}
      }
      return {title: nth && mw ? `매월 ${nth}째 주 ${KO_DAY[mw] || mw}요일` : '매월 (미정)'}
    },
  },
})

/** 이용 옵션 1종 = 한 카드(일반권/하프권/종일권). 이용시간은 시간블록/키반납에서 파생(저장 안 함). */
export const venueTicket = defineType({
  name: 'venueTicket',
  title: '이용 옵션',
  type: 'object',
  fields: [
    defineField({name: 'name', title: '이름', type: 'string', description: '예: 일반권, 하프권, 종일권'}),
    defineField({
      name: 'disciplines', title: '대상 종목', type: 'array',
      of: [{type: 'string'}], options: {list: DISCIPLINE_LIST},
      description: 'BE discipline code. 같은 가격이면 멀티 태그, 가격 다르면 카드를 나눠 등록.',
      validation: (r) => r.min(1),
    }),
    defineField({name: 'weekday', title: '평일', type: 'venueDaypart', validation: (r) => r.required()}),
    defineField({name: 'weekend', title: '주말·공휴일', type: 'venueDaypart'}),
  ],
  // 같은 이름(예: 일반권 3시간)이 종목별로 겹칠 수 있어 종목명을 앞에 붙인다: "프리다이빙·머메이드 / 일반권 (3시간)".
  preview: {
    select: {name: 'name', disciplines: 'disciplines'},
    prepare: ({name, disciplines}) => {
      const discs = (disciplines || []).map((d: string) => KO_DISC[d] || d).join('·')
      const label = name || '이용 옵션'
      return {title: discs ? `${discs} / ${label}` : label}
    },
  },
})

export const venue = defineType({
  name: 'venue',
  title: '위치(공식 수영장)',
  type: 'document',
  fieldsets: [
    // 도로명+세부를 한 묶음으로 — 한 위치의 주소라 떨어뜨리지 않고 바짝 붙인다.
    {name: 'addr', title: '주소', options: {columns: 1}},
    // 위도·경도를 한 줄(2열)로 묶어 세로 길이/간격 축소.
    {name: 'geo', title: '좌표 (위도·경도)', options: {columns: 2}},
  ],
  fields: [
    defineField({name: 'name', title: '장소 이름', type: 'string', validation: (r) => r.required()}),
    defineField({
      name: 'type', title: '유형', type: 'string',
      options: {list: [
        {title: '일반 수영장', value: 'SWIMMING_POOL'},
        {title: '잠수풀', value: 'DIVING_POOL'},
        {title: '딥풀', value: 'DEEP_POOL'},
        {title: '해양', value: 'OCEAN'},
      ]},
      description: '거친 분류. 정확한 깊이는 아래 "최대수심" 으로 별도 입력.',
      validation: (r) => r.required(),
    }),
    defineField({
      name: 'maxDepth', title: '최대수심(m)', type: 'number', validation: (r) => r.min(0),
      description: '해양 등 미상이면 비워둠. 유형과 별개의 정확값.',
    }),
    defineField({
      name: 'address', title: '도로명주소', type: 'string', fieldset: 'addr',
      description: '위/경도 기준이 되는 정식 도로명주소.',
    }),
    defineField({
      name: 'addressDetail', title: '세부주소', type: 'string', fieldset: 'addr',
      description: '동·호수 등 도로명주소로 안 잡히는 직접입력분(선택).',
    }),
    // 지도 핀 — geopoint(위/경/고도 스택)이 너무 길어 위도·경도 숫자 2칸으로(고도 불필요). 구글맵에서 좌표 복사.
    defineField({name: 'latitude', title: '위도', type: 'number', fieldset: 'geo'}),
    defineField({name: 'longitude', title: '경도', type: 'number', fieldset: 'geo'}),
    // 정보 제공용 — 이미지만. 영상은 의도적으로 제외(트랜스코딩/스트리밍 서드파티 회피). type:'image' 라 이미지 자산만 허용.
    defineField({name: 'photos', title: '장소 사진', type: 'array', of: [{type: 'image'}], options: {layout: 'grid'}}),
    // 장비 대여료는 위치에 두지 않는다 — 코스 개설 시 강사가 지정. 위치엔 '장비 대여 정보'(자유 텍스트)만.
    defineField({
      name: 'equipInfo', title: '장비 대여 정보', type: 'text', rows: 3,
      description: '대여 가능 장비·슈트·요금 등 자유 서술(멀티라인).',
    }),
    defineField({name: 'closures', title: '정기 휴무', type: 'array', of: [{type: 'venueClosure'}]}),
    defineField({
      name: 'tickets', title: '이용 옵션(운영 세션)', type: 'array', of: [{type: 'venueTicket'}],
      validation: (r) => r.min(1).error('이용 옵션 최소 1개'),
    }),
    defineField({name: 'sortOrder', title: '정렬', type: 'number', initialValue: 0}),
    defineField({name: 'active', title: '노출', type: 'boolean', initialValue: true}),
  ],
  orderings: [
    {title: '정렬순', name: 'sortOrderAsc', by: [{field: 'sortOrder', direction: 'asc'}]},
  ],
  preview: {select: {title: 'name', subtitle: 'type'}},
})
