package com.diving.pungdong.instructorapplication.dto;

import lombok.*;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 강사 신청 제출(2-phase 2단계). 종목 + 본인확인 id + (조건부) 자격증 목록.
 *
 * <p>자격증은 <b>내 자격증의 id</b> 로 참조한다(여러 단체 가능). 필요 여부는 종목의 {@code requiresCertification} —
 * 필요 종목은 1건 이상, 불필요(수영/서핑)는 생략 가능. 그래서 bean-validation 으로 강제하지 않고 서비스에서 조건부 검증한다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class InstructorApplicationSubmitRequest {

    /** 신청 종목 코드 (GET /disciplines 의 code). */
    @NotEmpty
    private String disciplineCode;

    @NotNull
    private Long verificationId;

    /**
     * 첨부할 내 자격증 id ({@code GET /certificates/mine}). 본인 소유·신청 종목 일치·강사 레벨이어야 한다(아니면 400 + msg).
     * 자격증 필요 종목은 (자동 첨부 포함) 1건 이상, 불필요 종목(수영/서핑)은 생략 가능.
     * 그 종목의 검증 상태 NONE 인 강사레벨 자격증은 <b>빼도 자동 첨부</b>된다 — 어드민이 한 번에 본다.
     */
    private List<Long> certificateIds;

    /**
     * (선택) 다이빙보험 증빙 이미지의 저장 참조 key — 업로드 응답(POST /certificate-images)의 {@code fileKey}.
     * 옵셔널이라 검증 안 함. 자격증과 동일한 비공개 이미지(presigned 열람).
     */
    private String insuranceFileKey;
}
