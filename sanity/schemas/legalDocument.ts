import {defineType, defineField} from 'sanity'

/**
 * 법적 고지 문서 — 이용약관 / 개인정보처리방침 / 취소·환불 정책의 **전문(full page)**.
 * 웹 라우트 `/{slug}` (terms·privacy·refund) + 푸터 모달에서 그대로 렌더된다.
 *
 * `term`(약관/동의)과는 다른 축이다 — 헷갈리지 말 것:
 *   - `term`        = 화면별 **동의 체크박스**(요약 + "자세히" 본문). BE 가 서버사이드로 읽어 동의 이력을 박제.
 *   - `legalDocument` = **공개 게시 전문 페이지**. 표시 전용, FE 가 Sanity CDN 직접 읽기(useCdn:true).
 * (앱 심사·법적 고지 표면이 요구하는 "전문 한 장" 이라 별도 타입. 정책은 docs/features/consent-and-terms.md.)
 *
 * 본문은 Portable Text → FE `<PortableTextBody/>` 가 렌더. ⚠️ 아래 block 설정의 styles/lists/marks 집합은
 * FE 렌더러가 스타일링하는 집합과 **정확히 일치**해야 의도대로 보인다. 새 스타일을 켜기 전에 FE 렌더러부터 확장.
 *
 * FE GROQ (sanity/queries.ts `legalDocumentBySlug` 단일 출처):
 *   *[_type == "legalDocument" && slug.current == $slug && active == true][0]
 */
const ALLOWED_SLUGS = ['terms', 'privacy', 'refund']

export const legalDocument = defineType({
  name: 'legalDocument',
  title: '법적 고지 문서',
  type: 'document',
  fields: [
    defineField({name: 'title', title: '제목', type: 'string', validation: (r) => r.required()}),
    defineField({
      name: 'slug',
      title: 'Slug (URL)',
      type: 'slug',
      options: {source: 'title', maxLength: 32},
      description: `라우트 = /{slug}. 반드시 ${ALLOWED_SLUGS.join(' / ')} 중 하나.`,
      validation: (r) =>
        r.required().custom((slug) => {
          const value = (slug as {current?: string} | undefined)?.current
          if (!value) return true // required 가 따로 잡음
          return ALLOWED_SLUGS.includes(value)
            ? true
            : `slug 은 ${ALLOWED_SLUGS.join(' / ')} 중 하나여야 합니다 (라우트가 깨짐).`
        }),
    }),
    defineField({
      name: 'body',
      title: '본문',
      type: 'array',
      validation: (r) => r.required(),
      of: [
        {
          type: 'block',
          // ⚠️ FE 렌더러(PortableTextBody)와 일치 — 아래 집합만 노출. H1/H4/image 등은 켜지 말 것.
          styles: [
            {title: '본문', value: 'normal'},
            {title: '제목 H2', value: 'h2'},
            {title: '소제목 H3', value: 'h3'},
            {title: '인용', value: 'blockquote'},
          ],
          lists: [
            {title: '불릿', value: 'bullet'},
            {title: '번호', value: 'number'},
          ],
          marks: {
            decorators: [
              {title: '굵게', value: 'strong'},
              {title: '기울임', value: 'em'},
            ],
            annotations: [
              {
                name: 'link',
                type: 'object',
                title: '링크',
                fields: [{name: 'href', type: 'url', title: 'URL'}],
              },
            ],
          },
        },
      ],
    }),
    defineField({
      name: 'version',
      title: '개정 버전',
      type: 'string',
      description: '표시용 개정 버전 (예: 1.0). 의미가 바뀌는 개정 시 올릴 것.',
    }),
    defineField({name: 'effectiveDate', title: '시행일', type: 'date'}),
    defineField({name: 'active', title: '활성', type: 'boolean', initialValue: true}),
  ],
  preview: {select: {title: 'title', subtitle: 'slug.current'}},
})
