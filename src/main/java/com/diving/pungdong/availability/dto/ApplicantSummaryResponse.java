package com.diving.pungdong.availability.dto;

import lombok.*;

import java.util.List;

/**
 * 슬롯 안 학생 1명 요약 — V2 디자인의 슬롯 신청자 행(이름 · 단체·레벨 · 대여 장비). 강사측 수강관리
 * (enrollment) day 캘린더 이벤트 블록과 통일된 어휘.
 *
 * <p><b>v1 에서는 항상 빈 배열</b>로 응답한다 — 풍덩 수강생 신청(pending/confirmed)은 enrollment/booking
 * 도메인 산물인데 BE 에 아직 그 도메인이 없다(lecture/reservation 재설계 대기). 응답 <b>모양만</b> 미리
 * 잡아 두어 enrollment 가 붙을 때 forward-compatible 하게 채운다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ApplicantSummaryResponse {

    /** 학생 이름. */
    private String name;

    /** "AIDA · L2 · 중급" 같은 단체·레벨 한 줄 요약. */
    private String courseTag;

    /** 이번 세션 대여 장비 라벨 배열. */
    private List<String> gear;

    /** 'external' 이면 외부 플랫폼 점유 행, null 이면 풍덩 학생. */
    private String kind;
}
