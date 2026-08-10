package com.diving.pungdong.branding.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 게시물 작성·수정 — 수정도 <b>스냅샷 교체</b>다(미디어·태그를 통째로 갈아끼운다). 사진 순서 변경이
 * 일상 동작이라 부분 갱신보다 원자적 교체가 안전하다(기록·course 와 같은 관례).
 */
@Getter @Setter
@NoArgsConstructor
public class BrandingPostRequest {

    /**
     * 업로드해서 받은 CDN URL 들. <b>배열 순서가 곧 표시 순서</b>이고 <b>0번이 그리드 썸네일</b>이다.
     * 우리 CDN base 로 시작하는지 서버가 검증한다 — 임의 외부 URL 을 본문에 심지 못하게.
     */
    @NotNull(message = "사진을 한 장 이상 올려주세요.")
    @Size(min = 1, max = 10, message = "사진은 1장 이상 10장까지 올릴 수 있어요.")
    private List<String> mediaUrls = new ArrayList<>();

    @Size(max = 2000, message = "본문은 2000자까지 쓸 수 있어요.")
    private String caption;

    @Size(max = 10, message = "태그는 10개까지 달 수 있어요.")
    private List<@Size(min = 1, max = 30, message = "태그는 30자까지 쓸 수 있어요.") String> tags = new ArrayList<>();

    @Size(max = 60, message = "위치는 60자까지 쓸 수 있어요.")
    private String locationLabel;

    /** 연결할 내 강의. 강사가 아니거나 남의 코스면 400. 안 보내면 연결 없음. */
    private Long linkedCourseId;
}
