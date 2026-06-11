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
 * 특정 화면의 약관 목록. params: { context }
 *   예: 'signup' | 'identity_verification' | 'instructor_application' | 'payment'
 * (전체동의 + 개별 [필수] + 자세히 UI 에 사용)
 */
export const termsByContext = `
*[_type == "term" && active == true && $context in contexts]
  | order(sortOrder asc) { key, title, required, summary, body, version }
`
