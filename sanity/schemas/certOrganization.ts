import {defineType, defineField} from 'sanity'

/**
 * 자격증 발급 단체. 강사 신청 화면의 "단체 선택" 드롭다운 소스.
 *
 * - `code` 는 BE 로 그대로 전송되는 값(`certificates[].organizationCode`). BE discipline 처럼
 *   대문자 컨벤션. 한 번 정하면 바꾸지 말 것(제출 데이터가 이 값을 가리킴).
 * - 한 단체가 여러 종목에 속할 수 있음 (예: AIDA → FREEDIVING + SCUBA).
 * - 종목 코드(`disciplines`)는 BE `discipline.code` 와 1:1 (FREEDIVING / SCUBA ...).
 */
export const certOrganization = defineType({
  name: 'certOrganization',
  title: '자격증 발급 단체',
  type: 'document',
  fields: [
    defineField({
      name: 'name',
      title: '표시명 (목록 굵은 제목)',
      type: 'string',
      description: '리스트에 굵게 보이는 짧은 이름. 예: PADI, SDI/TDI, AIDA',
      validation: (r) => r.required(),
    }),
    defineField({
      name: 'fullName',
      title: '정식 명칭 (부제)',
      type: 'string',
      description:
        '표시명 아래 부제로 노출되는 단체 정식 명칭. 예: Professional Association of Diving Instructors / 국제프리다이빙협회 / 세계수중연맹 (World Underwater Federation)',
    }),
    defineField({
      name: 'code',
      title: '코드 (BE 전송값, 대문자)',
      type: 'string',
      description: 'organizationCode 로 BE 에 전송. 예: AIDA, PADI, SSI, NAUI, CMAS, MOLCHANOVS, OTHER',
      validation: (r) => r.required().uppercase(),
    }),
    defineField({
      name: 'disciplines',
      title: '해당 종목',
      type: 'array',
      of: [{type: 'string'}],
      description: 'BE discipline code. 한 단체가 여러 종목 가능 (예: AIDA → FREEDIVING + SCUBA)',
      options: {
        list: [
          {title: '프리다이빙', value: 'FREEDIVING'},
          {title: '스쿠버다이빙', value: 'SCUBA'},
        ],
      },
      validation: (r) => r.min(1),
    }),
    defineField({name: 'sortOrder', title: '정렬', type: 'number', initialValue: 0}),
    defineField({name: 'active', title: '노출', type: 'boolean', initialValue: true}),
  ],
  orderings: [
    {title: '정렬순', name: 'sortOrderAsc', by: [{field: 'sortOrder', direction: 'asc'}]},
  ],
  preview: {select: {title: 'name', subtitle: 'code'}},
})
