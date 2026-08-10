import {defineType, defineField} from 'sanity'

/**
 * 사이트 전역 설정 (싱글톤) — 런칭 상태/데모 노출을 **무배포로** 토글하는 단일 스위치.
 *
 * FE 는 Sanity CDN 에서 직접 읽어 배너/태그를 띄우고, BE 는 서버사이드로 읽어(짧은 캐시) 신청 차단·
 * 데모 필터를 강제한다. 값 하나만 바꿔 publish 하면 FE/BE 양쪽 다 재배포 없이 정식 런칭으로 전환된다.
 *
 *   BE: *[_type == "siteSettings"][0]{launched, showSeededCourses, pendingTtlHours, paymentTtlHours, proposalTtlHours}
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
    defineField({
      name: 'pendingTtlHours',
      title: '강사 결정 대기 만료(시간)',
      type: 'number',
      description:
        '⚠️ 돈이 걸린 값. 학생이 결제를 마친 뒤(결제완료·강사 확인 대기) 이 시간 안에 강사가 수락/거절 안 하면 ' +
        '자동 만료되고 전액 자동환불된다(결제 시각부터 계산). 비우면 BE 기본값 24h. ' +
        '짧게 잡으면 바쁜 강사의 건이 자동 환불돼 거래가 깨지니 신중히.',
      initialValue: 24,
      validation: (Rule) => Rule.min(1).integer(),
    }),
    defineField({
      name: 'paymentTtlHours',
      title: '미결제 만료(시간)',
      type: 'number',
      description:
        '학생이 신청만 하고 이 시간 안에 결제하지 않으면 자동 만료(좌석 해제, 환불 없음). ' +
        '시계는 강사 수락이 아니라 신청 시각부터 돈다 — 선결제라 신청 직후가 곧 결제 시점이다. ' +
        '비우면 BE 기본값 12h. 결제는 몇 분이면 끝나는 행위라 12h 는 좌석을 과하게 묶는다(1h 수준 권장). ' +
        '만료돼도 학생은 그냥 다시 신청하면 되고(옛 건은 자동으로 갈아끼워짐) 그때 시계도 새로 시작된다.',
      initialValue: 12,
      validation: (Rule) => Rule.min(1).integer(),
    }),
    defineField({
      name: 'proposalTtlHours',
      title: '강사 제안 슬롯 만료(시간)',
      type: 'number',
      description:
        '강사가 일정변경으로 제안한 슬롯(최대 3개)의 유효시간. 제안하는 순간 그 자리들의 좌석을 잡아두므로 ' +
        '(학생이 고르면 만석으로 막히지 않게), 이 시간이 지나면 제안이 사라지고 잡아둔 좌석이 풀린다. ' +
        '비우면 BE 기본값 6h. 길게 잡으면 남의 좌석을 오래 묶으니 신중히.',
      initialValue: 6,
      validation: (Rule) => Rule.min(1).integer(),
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
