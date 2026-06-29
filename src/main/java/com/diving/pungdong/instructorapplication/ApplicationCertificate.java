package com.diving.pungdong.instructorapplication;

import lombok.*;

import javax.persistence.*;

/**
 * 강사 신청에 첨부된 자격증 1건 = (발급 단체 + 이미지). 신청에 종속 (1:N) — 한 종목 신청에
 * 여러 자격증(예: AIDA + PADI + Molchanovs)을 등록할 수 있고, 상위 자격 취득 시 추가된다.
 * 재제출 시 신청과 함께 교체되는 스냅샷.
 *
 * <p>단체(organizationCode)는 신청이 아니라 <b>자격증 단위</b>다 — 한 사람이 종목 안에서 여러
 * 단체 자격을 가질 수 있어서. (향후 레벨/등급 ratingCode 도 여기에 붙는다.)
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

    /** Sanity 자격증 카탈로그의 단체 code (예: "AIDA", "PADI", "OTHER"). 종목별 단체 목록은 Sanity. */
    private String organizationCode;

    /** organizationCode 가 "OTHER" 일 때 직접입력 단체명. */
    private String organizationOther;

    /**
     * 이미지 저장 참조 — S3 객체 key(비공개 업로드) 또는 로컬 서빙 URL. 공개 URL 이 아니므로
     * 열람은 조회 시점에 presigned 로 변환해 노출한다. (컬럼명은 {@code file_url} 유지 — 마이그레이션 불요.)
     */
    @Column(name = "file_url")
    private String fileKey;

    /** 업로드/표시 순서 (0-base). */
    private int sortOrder;
}
