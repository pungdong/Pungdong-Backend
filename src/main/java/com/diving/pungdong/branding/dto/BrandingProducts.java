package com.diving.pungdong.branding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * CTA 버튼의 상품 개수 — <b>강사만</b>.
 *
 * <p>투어({@code tours})는 {@code CourseKind} 에 TOUR 자체가 없어 이번 범위에 없다(D4). CTA 는
 * "강의 보기" 한 버튼으로 렌더된다.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrandingProducts {

    /** 공개(OPEN) 강의 수. 데모 시드 코스 취급은 {@code SiteSettings.showSeededCourses} 를 따른다 — 둘러보기와 숫자가 어긋나지 않게. */
    private Integer lessons;
}
