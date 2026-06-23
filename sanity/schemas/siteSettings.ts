import {defineType, defineField} from 'sanity'

/**
 * 사이트 전역 설정 (싱글톤) — 런칭 상태/데모 노출을 **무배포로** 토글하는 단일 스위치.
 *
 * FE 는 Sanity CDN 에서 직접 읽어 배너/태그를 띄우고, BE 는 서버사이드로 읽어(짧은 캐시) 신청 차단·
 * 데모 필터를 강제한다. 값 하나만 바꿔 publish 하면 FE/BE 양쪽 다 재배포 없이 정식 런칭으로 전환된다.
 *
 *   BE: *[_type == "siteSettings"][0]{launched, showSeededCourses}
 *
 * 싱글톤이라 문서는 1개만 둔다 (Studio 에서 "사이트 설정" 단일 문서로 관리).
 */
export const siteSettings = defineType({
  name: 'siteSettings',
  title: '사이트 설정',
  type: 'document',
  fields: [
    defineField({
      name: 'launched',
      title: '정식 런칭됨',
      type: 'boolean',
      description:
        'false 면 전 코스(실강사 것 포함) 예약/신청 차단 + FE 가 "정식 런칭을 기다려주세요" 배너 표시. ' +
        '런칭 준비가 되면 true 로 바꿔 publish → 무배포로 신청 개방.',
      initialValue: false,
    }),
    defineField({
      name: 'showSeededCourses',
      title: '데모(샘플) 코스 노출',
      type: 'boolean',
      description:
        'true 면 시드로 만든 데모 강의/투어를 둘러보기에 노출(FE 가 "샘플용" 태그). 실강사 코스가 충분히 ' +
        '차면 false 로 바꿔 publish → 데모는 DB 에 남되 공개 목록에서 사라짐.',
      initialValue: true,
    }),
  ],
  preview: {
    select: {launched: 'launched', show: 'showSeededCourses'},
    prepare: ({launched, show}) => ({
      title: '사이트 설정',
      subtitle: `런칭=${launched ? 'ON' : 'OFF'} · 데모노출=${show ? 'ON' : 'OFF'}`,
    }),
  },
})
