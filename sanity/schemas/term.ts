import {defineType, defineField} from 'sanity'

/**
 * 약관 / 동의 — 앱 전체 약관을 한 타입에 모은다(목적별 타입 분리 X). 화면별 노출은 `contexts`
 * 로 스코프(한 약관이 여러 화면에 재사용 가능). 본문은 Portable Text → FE 에서 <PortableText/> 렌더.
 *
 * FE 는 화면 컨텍스트로 필터:
 *   *[_type == "term" && active && $context in contexts] | order(sortOrder asc)
 */
export const term = defineType({
  name: 'term',
  title: '약관 / 동의',
  type: 'document',
  fields: [
    defineField({name: 'title', title: '제목', type: 'string', validation: (r) => r.required()}),
    defineField({
      name: 'key',
      title: '식별 키 (FE 참조용)',
      type: 'string',
      description: '예: privacy_collect, unique_id_ci_di, service_terms, marketing, location',
      validation: (r) => r.required(),
    }),
    defineField({
      name: 'contexts',
      title: '노출 화면',
      type: 'array',
      of: [{type: 'string'}],
      description: '이 약관이 노출되는 화면들 (한 약관이 여러 화면 가능)',
      options: {
        list: [
          {title: '회원가입', value: 'signup'},
          {title: '본인확인', value: 'identity_verification'},
          {title: '강사신청', value: 'instructor_application'},
          {title: '결제', value: 'payment'},
        ],
      },
      validation: (r) => r.min(1),
    }),
    defineField({name: 'required', title: '필수 동의', type: 'boolean', initialValue: true}),
    defineField({name: 'summary', title: '한 줄 요약 (체크박스 옆)', type: 'string'}),
    defineField({name: 'body', title: '본문 ("자세히" 전문)', type: 'array', of: [{type: 'block'}]}),
    defineField({
      name: 'version',
      title: '버전',
      type: 'string',
      description:
        'BE 동의기록이 (key, version) 으로 이 버전을 박제·참조한다. 의미가 바뀌는 개정 시 반드시 올릴 것 (예: v1 → v2). 오타 등 사소한 수정은 그대로.',
      initialValue: 'v1',
      // 본문(body)이 직전 발행본과 달라졌는데 version 을 그대로 두면 publish 차단.
      // → 관리자(신뢰 주체)의 "bump 깜빡"을 에디터 단에서 잡는다. 일반 유저(비신뢰)는 BE 가 막음.
      validation: (Rule) =>
        Rule.required().custom(async (version, context) => {
          const doc = context.document
          if (!doc?._id) return true
          const publishedId = doc._id.replace(/^drafts\./, '')
          const published = await context
            .getClient({apiVersion: '2024-01-01'})
            .fetch('*[_id == $id][0]{version, body}', {id: publishedId})
          if (!published) return true // 최초 발행 — 비교 대상 없음
          const bodyChanged =
            JSON.stringify(published.body ?? null) !== JSON.stringify(doc.body ?? null)
          if (bodyChanged && published.version === version) {
            return '본문이 변경되었습니다 — version 을 올려주세요 (예: v1 → v2). 동의 기록이 버전으로 구분됩니다.'
          }
          return true
        }),
    }),
    defineField({name: 'effectiveAt', title: '시행일', type: 'datetime'}),
    defineField({name: 'sortOrder', title: '정렬', type: 'number', initialValue: 0}),
    defineField({name: 'active', title: '노출', type: 'boolean', initialValue: true}),
  ],
  orderings: [
    {title: '정렬순', name: 'sortOrderAsc', by: [{field: 'sortOrder', direction: 'asc'}]},
  ],
  preview: {select: {title: 'title', subtitle: 'key'}},
})
