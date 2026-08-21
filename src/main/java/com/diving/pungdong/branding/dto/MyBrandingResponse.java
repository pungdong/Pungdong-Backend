package com.diving.pungdong.branding.dto;

import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 오너 편집용 원본 — {@code GET /branding/me} (인증).
 *
 * <p><b>조회가 생성하지 않는다</b> — 생성은 첫 쓰기가 한다(contract §4.5). 미작성이면
 * {@code exists:false} 로 알려주되, <b>계정에서 파생되는 값은 그대로 채워 보낸다</b>(닉네임·아바타·
 * 인증마크·자격·검수 상태). 비는 건 프로필 행이 소유하는 tagline·bio·활동지역·기록뿐이다 —
 * 그래야 오너가 첫 작성 전에도 <b>"내 페이지가 남에게 이렇게 보인다"</b> 를 열어볼 수 있다
 * (그 계정도 공개 프로필이 열린다, 2026-08-21 D10). 예전엔 {@code {"exists": false}} 만 내려가
 * 미리보기 링크를 만들 닉네임조차 없었다.
 *
 * <p>{@code reviewStatus}/{@code approvedAt} 은 <b>강사 신청 이력이 있을 때만</b> 실린다. 이력이
 * 없으면(D2 의 일반 유저) 키가 아예 빠지고, FE 는 검수 배너를 렌더하지 않는다 — 일반 유저에겐 검수
 * 개념이 없다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class MyBrandingResponse {

    /**
     * 프로필 행이 있나 = <b>한 번이라도 뭔가 적었나.</b> "공개 페이지가 있나" 가 아니다 — 그건 모든
     * 살아있는 계정에 있다. FE 의 온보딩 CTA 분기용이고, false 여도 아래 파생 필드는 채워져 있다.
     */
    private boolean exists;

    /**
     * 미작성({@code exists=false})이면 <b>키를 생략한다</b> — 그래서 원시 {@code boolean} 이 아니라 래퍼다.
     * 원시로 두면 만들지도 않은 프로필이 {@code isPublished:false} 로 내려가 "비공개로 존재한다"처럼 읽힌다.
     * <b>이 응답에서 미작성일 때 비는 유일한 파생 아닌 키다</b> — 프로필 행의 상태라 파생할 것이 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("isPublished")
    private Boolean isPublished;

    private String nickName;
    private String avatarUrl;

    /** 유저가 비웠으면 null 그대로. */
    private String tagline;
    private String bio;
    private String locationLabel;

    /** 미작성이면 빈 배열 — {@code null} 을 내려보내면 FE 가 배열로 다루다 터진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<RecordDto> records;

    /** 인증마크·워딩 분기용. 강사 신청에서 파생하므로 미작성이어도 온다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("isInstructor")
    private Boolean isInstructor;

    /** 강사만. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> disciplineCodes;

    /** 강사만 — 승인된 강사 신청에서 파생. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<BrandingProfileResponse.CertBadge> certs;

    /** 파생 통계. 오너 뷰의 수강생 수는 공개 응답이 비공개 시 400 이라 여기서만 얻을 수 있다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BrandingStats stats;

    /** 강사만. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BrandingProducts products;

    /** 강사 신청 이력이 있을 때만. 없으면 키 생략. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private InstructorApplicationStatus reviewStatus;

    /**
     * 승인 시각 — 웹 검수 배너가 "검수 통과 2026.05.13" 처럼 날짜를 직접 렌더한다. APPROVED 일 때만.
     * 값은 기존 {@code InstructorApplication.reviewedAt} 을 그대로 쓴다(컬럼 추가 없음).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OffsetDateTime approvedAt;

}
