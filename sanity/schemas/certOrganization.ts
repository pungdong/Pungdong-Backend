import {defineType, defineField} from 'sanity'

/** 종목 코드 → 한글 (자격증 preview 의 종목 prefix 용). BE discipline.code 와 1:1. */
const DISC_KO: Record<string, string> = {FREEDIVING: '프리다이빙', SCUBA: '스쿠버다이빙', MERMAID: '머메이드'}

/** 평탄화 레벨 — 단체마다 명칭은 달라도(예: "Advanced Freediver") 사다리는 이 5종으로 정규화. */
const CERT_LEVELS = [
  {title: '레벨 1', value: 'LEVEL_1'},
  {title: '레벨 2', value: 'LEVEL_2'},
  {title: '레벨 3', value: 'LEVEL_3'},
  {title: '레벨 4', value: 'LEVEL_4'},
  {title: '강사 (Instructor)', value: 'INSTRUCTOR'},
  {title: '강사 양성 (Instructor Trainer)', value: 'INSTRUCTOR_TRAINER'},
]
const LEVEL_KO: Record<string, string> = Object.fromEntries(CERT_LEVELS.map((l) => [l.value, l.title]))

/**
 * 자격증 1종 = 한 단체가 한 종목에서 발급하는 등급. 단체별 명칭(displayName)은 제각각이지만
 * `level` 로 평탄화돼 단체 간 비교/코스 레벨 선택의 공통 축이 된다.
 *
 * - `disciplineCode` 는 상위 단체 `disciplines` 안의 값이어야 한다(검증).
 * - `level` 은 BE 가 enum 으로 아는 값(LEVEL_1~4 / INSTRUCTOR). `displayName` 은 표시 전용.
 * - 강사 신청(본인 자격 레벨) + 코스 작성(목표 레벨) 두 곳이 같은 카탈로그를 읽는다.
 */
export const certification = defineType({
  name: 'certification',
  title: '자격증(등급)',
  type: 'object',
  fields: [
    defineField({
      name: 'disciplineCode',
      title: '종목',
      type: 'string',
      options: {list: [
        {title: '프리다이빙', value: 'FREEDIVING'},
        {title: '스쿠버다이빙', value: 'SCUBA'},
        {title: '머메이드', value: 'MERMAID'},
      ]},
      validation: (r) =>
        r.required().custom((value, context) => {
          const discs = (context.document?.disciplines as string[]) || []
          if (value && discs.length && !discs.includes(value)) {
            return '단체가 다루는 종목(상단 "해당 종목") 안에서 골라야 합니다'
          }
          return true
        }),
    }),
    defineField({
      name: 'level', title: '평탄화 레벨', type: 'string',
      options: {list: CERT_LEVELS},
      description: '단체 명칭과 무관한 공통 사다리. 코스/신청에서 단체 간 비교 축.',
      validation: (r) => r.required(),
    }),
    defineField({
      name: 'displayName', title: '단체 명칭', type: 'string',
      description: '단체가 부르는 이름. 예: Advanced Freediver, AIDA 2, PADI Advanced Open Water',
      validation: (r) => r.required(),
    }),
    defineField({name: 'sortOrder', title: '정렬', type: 'number', initialValue: 0}),
    defineField({name: 'active', title: '노출', type: 'boolean', initialValue: true}),
  ],
  preview: {
    select: {displayName: 'displayName', disciplineCode: 'disciplineCode', level: 'level'},
    prepare: ({displayName, disciplineCode, level}) => ({
      title: `${DISC_KO[disciplineCode] || disciplineCode || '종목?'} / ${displayName || '이름?'}`,
      subtitle: LEVEL_KO[level] || level,
    }),
  },
})

/**
 * 자격증 발급 단체. 강사 신청 화면의 "단체 선택" 드롭다운 소스.
 *
 * - `code` 는 BE 로 그대로 전송되는 값(`certificates[].organizationCode`). BE discipline 처럼
 *   대문자 컨벤션. 한 번 정하면 바꾸지 말 것(제출 데이터가 이 값을 가리킴).
 * - 한 단체가 여러 종목에 속할 수 있음 (예: AIDA → FREEDIVING + SCUBA).
 * - 종목 코드(`disciplines`)는 BE `discipline.code` 와 1:1 (FREEDIVING / SCUBA ...).
 * - `certifications` = 그 단체가 발급하는 등급 카탈로그(종목별·평탄화 레벨). 코스 작성의
 *   "단체 → 레벨" 선택과 강사 신청의 본인 레벨 선택이 이걸 읽는다.
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
          {title: '머메이드', value: 'MERMAID'},
        ],
      },
      validation: (r) => r.min(1),
    }),
    defineField({
      name: 'certifications', title: '자격증(등급) 카탈로그', type: 'array',
      of: [{type: 'certification'}],
      description: '이 단체가 발급하는 등급들. 종목별·평탄화 레벨(LEVEL_1~4 / INSTRUCTOR).',
    }),
    defineField({name: 'sortOrder', title: '정렬', type: 'number', initialValue: 0}),
    defineField({name: 'active', title: '노출', type: 'boolean', initialValue: true}),
  ],
  orderings: [
    {title: '정렬순', name: 'sortOrderAsc', by: [{field: 'sortOrder', direction: 'asc'}]},
  ],
  // 리스트에서 단체명 + 어떤 종목 단체인지 같이 보이게: "프리다이빙·머메이드 · PADI".
  preview: {
    select: {title: 'name', code: 'code', disciplines: 'disciplines'},
    prepare: ({title, code, disciplines}) => {
      const KO: Record<string, string> = {FREEDIVING: '프리다이빙', SCUBA: '스쿠버다이빙', MERMAID: '머메이드'}
      const discs = (disciplines || []).map((d: string) => KO[d] || d).join('·')
      return {title, subtitle: discs ? `${discs} · ${code}` : code}
    },
  },
})
