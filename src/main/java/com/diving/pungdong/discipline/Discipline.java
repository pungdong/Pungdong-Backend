package com.diving.pungdong.discipline;

import lombok.*;

import javax.persistence.*;

/**
 * 종목 (프리다이빙 / 스쿠버 / 수영 / 서핑 ...). 홈 화면 셀렉터 · 강사 신청 · (추후) 강의가 참조하는
 * 1급 개념.
 *
 * <p>코드 enum 이 아니라 테이블인 이유: (1) 종목 추가가 배포 없이 가능해야 하고, (2)
 * {@code requiresCertification} 은 강사 신청 시 자격증 필수 여부를 <b>BE 가 강제</b>하는 비즈니스
 * 규칙이며, (3) 강의/강사 필터·카운트 등 BE 쿼리 대상이라서. (Sanity 자격증 카탈로그와 달리 BE 소유.)
 */
@Entity
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Discipline {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 안정적 식별 코드 (예: "FREEDIVING"). 강의·강사신청이 이 문자열로 참조. */
    @Column(unique = true)
    private String code;

    /** 표시명 (예: "프리다이빙"). */
    private String name;

    /** 강사 신청 시 자격증(+발급단체) 필수 여부. 스쿠버/프리다이빙=true, 수영/서핑=false. */
    private boolean requiresCertification;

    /** 노출 여부 — 비활성 종목은 목록/신청에서 제외. */
    private boolean active;

    /** 셀렉터 정렬 순서. */
    private int sortOrder;
}
