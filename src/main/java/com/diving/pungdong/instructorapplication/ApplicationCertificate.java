package com.diving.pungdong.instructorapplication;

import lombok.*;

import javax.persistence.*;

/**
 * 강사 신청에 첨부된 자격증 이미지 1장 (S3 URL). 신청에 종속 (1:N) —
 * 재제출 시 신청과 함께 교체되는 스냅샷.
 *
 * <p>레거시 {@code account.InstructorCertificate} 와 별개다 (그건 Account 소유에 메타데이터
 * 없음). 이 도메인은 신청 단위로 자격증을 관리한다.
 */
@Entity
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ApplicationCertificate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private InstructorApplication application;

    private String fileURL;

    /** 업로드/표시 순서 (0-base). */
    private int sortOrder;
}
