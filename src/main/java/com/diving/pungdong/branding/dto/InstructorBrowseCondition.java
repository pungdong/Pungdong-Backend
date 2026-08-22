package com.diving.pungdong.branding.dto;

import lombok.*;

import java.util.List;

/**
 * 강사 둘러보기 조건 — {@code GET /instructors/browse}. 강의 둘러보기({@code CourseBrowseCondition})와
 * 같은 자리를 차지하지만 축이 다르다: 지역·레벨 필터가 <b>없다</b>.
 *
 * <p><b>왜 지역이 없나</b>: 강사의 활동지역은 {@code AccountBranding.locationLabel} 자유 텍스트(60자,
 * 예 "잠실 · 송파")라 {@code venue.Region} 으로 파생할 수 없다. 코스는 위치 주소가 있어 파생되지만
 * 강사에겐 그 신호가 없다. 억지로 파싱하면 둘러보기와 필터의 "지역" 이 갈린다.
 *
 * <p><b>왜 자격 레벨 필터가 없나</b>: 처음엔 강사 쪽에 등급 필드 자체가 없었다. 2026-08-22 수렴으로 VERIFIED
 * {@code StudentCertificate.level} 이 생겼지만 필터 축 추가는 v1.5(단체 칩만 낸다).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorBrowseCondition {

    /** 필수 — 화면이 항상 한 종목 컨텍스트로 진입한다. 누락이면 400. */
    private String disciplineCode;

    /** 강사 nickName 부분일치(대소문자 무시). 공백만이면 무시. */
    private String keyword;

    /** 자격증 단체 코드(Sanity 카탈로그 문자열). OR 합집합, 같은 종목 승인 신청 안에서만 매칭. */
    private List<String> organizationCodes;

    /** true 면 그 종목에 공개중인 강의가 1개 이상인 강사만. false/null 은 필터 없음. */
    private Boolean hasOpenCourse;

    private Sort sort;

    /**
     * 정렬 화이트리스트. 클라이언트가 임의 컬럼을 태울 수 없게 enum 만 받는다.
     * {@code COURSE_COUNT_DESC} 는 집계값 정렬이라 {@code Pageable} 의 {@code Sort} 로 표현할 수 없어
     * 레포 메서드가 갈라져 있다.
     */
    public enum Sort {
        /** 기본 — 최근 가입(account id desc). */
        LATEST,
        /** 공개중인 강의가 많은 순. 동수는 최근 가입 순. */
        COURSE_COUNT_DESC
    }
}
