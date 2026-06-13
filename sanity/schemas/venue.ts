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
 * 시간은 "HH:mm" 문자열(예: "08:00"). 권종(일반/하프/종일)은 새 축이 아니라 이용권 카드를 나눠 등록.
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
const DISCIPLINE_LIST = [
  {title: '프리다이빙', value: 'FREEDIVING'},
  {title: '스쿠버다이빙', value: 'SCUBA'},
]

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
  preview: {select: {t: 'type'}, prepare: ({t}) => ({title: t === 'WEEKLY' ? '매주 휴무' : '매월 휴무'})},
})

/** 이용권 1종 = 한 카드(일반권/하프권/종일권). 이용시간은 시간블록/키반납에서 파생(저장 안 함). */
export const venueTicket = defineType({
  name: 'venueTicket',
  title: '이용권',
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
  preview: {select: {title: 'name'}, prepare: ({title}) => ({title: title || '이용권'})},
})

export const venue = defineType({
  name: 'venue',
  title: '위치(공식 수영장)',
  type: 'document',
  fields: [
    defineField({name: 'name', title: '장소 이름', type: 'string', validation: (r) => r.required()}),
    defineField({
      name: 'type', title: '유형', type: 'string',
      options: {list: [
        {title: '5m 풀', value: 'POOL_5M'},
        {title: '딥풀', value: 'DEEP_POOL'},
        {title: '해양', value: 'OCEAN'},
      ]},
      validation: (r) => r.required(),
    }),
    defineField({name: 'address', title: '주소', type: 'string'}),
    defineField({name: 'location', title: '지도 핀', type: 'geopoint'}),
    // 정보 제공용 — 이미지만. 영상은 의도적으로 제외(트랜스코딩/스트리밍 서드파티 회피). type:'image' 라 이미지 자산만 허용.
    defineField({name: 'photos', title: '장소 사진', type: 'array', of: [{type: 'image'}], options: {layout: 'grid'}}),
    defineField({
      name: 'equipInfo', title: '장비 대여 정보', type: 'text', rows: 3,
      description: '대여 가능 장비·슈트·요금 등 자유 서술(멀티라인).',
    }),
    defineField({name: 'equipFee', title: '장비 대여료(1인당, 원)', type: 'number', validation: (r) => r.min(0)}),
    defineField({name: 'closures', title: '정기 휴무', type: 'array', of: [{type: 'venueClosure'}]}),
    defineField({
      name: 'tickets', title: '이용권(운영 세션)', type: 'array', of: [{type: 'venueTicket'}],
      validation: (r) => r.min(1).error('이용권 최소 1개'),
    }),
    defineField({name: 'sortOrder', title: '정렬', type: 'number', initialValue: 0}),
    defineField({name: 'active', title: '노출', type: 'boolean', initialValue: true}),
  ],
  orderings: [
    {title: '정렬순', name: 'sortOrderAsc', by: [{field: 'sortOrder', direction: 'asc'}]},
  ],
  preview: {select: {title: 'name', subtitle: 'type'}},
})
