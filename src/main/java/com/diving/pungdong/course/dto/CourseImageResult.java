package com.diving.pungdong.course.dto;

import lombok.*;

/** 2-phase 업로드 1단계 결과 — 업로드된 코스 이미지의 URL (생성 본문이 이 URL 을 참조). */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CourseImageResult {
    private String fileURL;
}
