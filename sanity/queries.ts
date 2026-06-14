/**
 * FE 에서 쓰는 GROQ 쿼리. @sanity/client 의 `client.fetch(query, params)` 로 호출.
 *
 *   import {createClient} from '@sanity/client'
 *   const sanity = createClient({ projectId, dataset: 'production', apiVersion: '2024-01-01', useCdn: true })
 *   const orgs = await sanity.fetch(orgsByDiscipline, { disciplineCode: 'FREEDIVING' })
 */

/** 특정 종목의 자격증 발급 단체 목록 (강사 신청 "단체 선택" 드롭다운). params: { disciplineCode } */
export const orgsByDiscipline = `
*[_type == "certOrganization" && active == true && $disciplineCode in disciplines]
  | order(sortOrder asc) { code, name, fullName }
`

/**
 * 한 단체가 한 종목에서 발급하는 자격증(등급) 목록 — 코스 작성 "단체 → 레벨" / 강사 신청 본인 레벨.
 * params: { code, disciplineCode }. `level`(평탄화)·`displayName`(단체 명칭) 둘 다 반환 →
 * UI 는 displayName 노출, 저장/비교는 level. BE 는 level 만 enum 으로 안다.
 */
export const certificationsByOrgAndDiscipline = `
*[_type == "certOrganization" && code == $code && active == true][0]
  .certifications[active == true && disciplineCode == $disciplineCode]
  | order(sortOrder asc) { level, displayName, disciplineCode }
`

/**
 * 특정 화면의 약관 목록. params: { context }
 *   예: 'signup' | 'identity_verification' | 'instructor_application' | 'payment'
 * (전체동의 + 개별 [필수] + 자세히 UI 에 사용)
 */
export const termsByContext = `
*[_type == "term" && active == true && $context in contexts]
  | order(sortOrder asc) { key, title, required, summary, body, version }
`

/** 이용권 daypart 투영(평일/주말 공통). */
const VENUE_DAYPART = `{ sold, fee, timeMode, blocks[]{ start, end }, open, close, holdHours }`

/**
 * 공식(OFFICIAL) 위치 목록 — 코스 빌더 "위치 선택". 종목 필터(이용권이 그 종목을 다루는 위치만).
 * params: { disciplineCode }  (전체를 원하면 빈/없는 값 대신 별도 쿼리)
 * FE 는 여기에 본인(강사)이 만든 커스텀 위치(BE `GET /venues`)를 합쳐 통합 리스트를 만든다.
 */
export const officialVenuesByDiscipline = `
*[_type == "venue" && active == true && $disciplineCode in tickets[].disciplines]
  | order(sortOrder asc) {
    _id, name, type, maxDepth, address, addressDetail, latitude, longitude, "photos": photos[].asset->url,
    equipInfo,
    closures[]{ type, weekdays, nth, monthlyWeekday },
    tickets[]{ "_key": _key, name, disciplines, weekday ${VENUE_DAYPART}, weekend ${VENUE_DAYPART} }
  }
`

/** 위치 1건 상세. params: { id } */
export const venueById = `
*[_type == "venue" && _id == $id][0] {
  _id, name, type, maxDepth, address, addressDetail, latitude, longitude, "photos": photos[].asset->url,
  equipInfo,
  closures[]{ type, weekdays, nth, monthlyWeekday },
  tickets[]{ "_key": _key, name, disciplines, weekday ${VENUE_DAYPART}, weekend ${VENUE_DAYPART} }
}
`

/**
 * (미래·BE 동기화용) 모든 위치의 리비전 토큰만 — read-side `_rev` 대조 백스톱.
 * BE 가 availability/부킹에서 OFFICIAL 을 캐싱할 때, 캐시된 _rev 와 비교해 바뀐 것만 refetch.
 * 비용이 바이트 단위라 짧은 주기로 돌려도 무방. 상세: docs/features/venue.md "캐싱·동기화·모니터링 설계".
 */
export const venueRevs = `*[_type == "venue"]{ _id, _rev }`
