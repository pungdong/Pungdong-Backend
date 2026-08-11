package com.diving.pungdong.branding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * 공개 브랜딩 페이지 / 내 프로필 — {@code GET /instructors/{nickName}} (비로그인 가능).
 *
 * <p><b>강사 한정 필드는 일반 유저 응답에서 키 자체가 빠진다</b>(D2) — {@code disciplineCodes}·
 * {@code certs} 에만 {@code @JsonInclude(NON_NULL)} 을 <b>필드 단위로</b> 건다. 반대로
 * {@code tagline}/{@code bio}/{@code locationLabel} 은 <b>유저가 지운 값</b>이므로 null 을 그대로
 * 내려보낸다 — FE 가 "아직 미구현"과 "유저가 지움"을 구분해야 편집 화면에서 덮어쓸지 판단할 수 있다
 * (contract §0).
 *
 * <p>검수 상태({@code reviewStatus}·{@code approvedAt})는 <b>공개 응답에 넣지 않는다</b> — 퍼블릭 뷰는
 * 검수 배너를 렌더하지 않는다("노출되는 강사는 모두 승인 받은 사람들"). 인증마크는 {@code isInstructor}
 * 하나로 그린다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class BrandingProfileResponse {

    private String nickName;
    private String avatarUrl;

    /** 유저가 비우면 {@code null} 을 그대로 내려준다(미구현과 구분). */
    private String tagline;
    private String bio;
    private String locationLabel;

    /**
     * 인증마크(공식 강사) 렌더 여부 = 승인(APPROVED)된 강사 신청 보유.
     *
     * <p><b>필드명에 {@code is} 를 붙이지 않는다.</b> Lombok 이 원시 {@code boolean isInstructor} 에
     * 만드는 게터는 {@code isInstructor()} 이고 Jackson 은 이걸 프로퍼티 {@code "instructor"} 로 본다 —
     * 필드의 {@code @JsonProperty("isInstructor")} 와 <b>서로 다른 두 프로퍼티</b>가 되어
     * {@code {"instructor":true,"isInstructor":true}} 가 나갔다(실측으로 확인, FE 는 계약대로
     * {@code isInstructor} 만 읽고 있어 값이 틀리진 않았다). 필드명을 {@code instructor} 로 두면
     * 게터가 만드는 이름과 일치해 {@code @JsonProperty} 가 그 하나를 개명한다.
     */
    @JsonProperty("isInstructor")
    private boolean instructor;

    /** 강사만. 일반 유저는 null → 키 자체가 빠진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> disciplineCodes;

    /** 강사만. 승인된 강사 신청에서 파생 — 자유입력 자격은 폐기됐다(D5). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<CertBadge> certs;

    /** 없으면 빈 배열 → FE 가 섹션 자체를 숨긴다. 일반 유저도 사용(D2). */
    private List<RecordDto> records;

    /** 파생 통계. 강사가 아니면 내부 필드가 비어 빈 객체가 된다. */
    private BrandingStats stats;

    /** CTA 상품 개수 — 강사만. 일반 유저는 null → 키 자체가 빠진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BrandingProducts products;

    /**
     * 자격 뱃지 — 승인된 강사 신청의 자격증에서 파생. {@code profile} 도메인의 동명 DTO 와 형태가 같지만
     * 패키지 간 단방향 의존을 지키려고 복제한다(branding → profile 의존을 만들지 않는다).
     */
    @Getter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class CertBadge {
        private String disciplineCode;
        private String organizationCode;
        private String organizationOther;
    }
}
