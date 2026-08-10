package com.diving.pungdong.branding.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.Size;

/**
 * 프로필 부분 수정 — {@code PATCH /branding/me}. 디자인의 오너 편집이 전체 폼 저장이 아니라
 * <b>필드마다 연필이 따로 붙은 인라인 수정</b>이라 PATCH 다.
 *
 * <p><b>키 생략 = 변경 없음 / 명시적 {@code null} = 비우기</b>. 둘을 구분해야 하므로 원시 필드로는
 * 표현할 수 없다 — 요청 JSON 에 키가 있었는지를 setter 호출 여부로 기록한다.
 */
@Getter
@NoArgsConstructor
public class BrandingUpdateRequest {

    @Size(max = 60, message = "한 줄 소개는 60자까지 쓸 수 있어요.")
    private String tagline;

    @Size(max = 500, message = "자기소개는 500자까지 쓸 수 있어요.")
    private String bio;

    @Size(max = 60, message = "활동 지역은 60자까지 쓸 수 있어요.")
    private String locationLabel;

    /** 요청 JSON 에 키가 있었는지 — 클라이언트가 직접 못 넣게 역직렬화에서 제외한다. */
    @JsonIgnore private boolean taglinePresent;
    @JsonIgnore private boolean bioPresent;
    @JsonIgnore private boolean locationLabelPresent;

    public void setTagline(String tagline) {
        this.tagline = tagline;
        this.taglinePresent = true;
    }

    public void setBio(String bio) {
        this.bio = bio;
        this.bioPresent = true;
    }

    public void setLocationLabel(String locationLabel) {
        this.locationLabel = locationLabel;
        this.locationLabelPresent = true;
    }
}
