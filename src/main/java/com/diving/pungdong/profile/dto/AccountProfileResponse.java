package com.diving.pungdong.profile.dto;

import com.diving.pungdong.account.Role;
import com.diving.pungdong.course.CertLevel;
import lombok.*;

import java.util.List;
import java.util.Set;

/**
 * 마이페이지 프로필 카드 — 본인({@code @CurrentUser}) 통합 조회. 기존 {@code AccountBasicInfo}(id/email/nickName/
 * roles)에 프로필 사진 + 자격 뱃지를 더한다. account 기본정보 ⊕ certificate 의 표시 뱃지를 합성한 응답.
 *
 * <p>career(경력)·rating(평점)은 데이터 모델 부재로 <b>이번 범위 제외</b> — rating 은 V2 Course 리뷰 평균으로 신설
 * 예정, career 는 보류.
 */
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AccountProfileResponse {
    private Long id;
    private String email;
    private String nickName;
    private Set<Role> roles;
    /** 프로필 사진 URL(미설정이면 null). */
    private String profilePhotoUrl;
    /**
     * 자격 뱃지 — <b>사람 표면 규칙</b>({@code certificate.CertificateBadgePolicy}): 수강생 레벨은 자기신고 그대로,
     * 강사 레벨은 VERIFIED 만, (종목,단체)별 최고 1장, 레벨 내림차순. <b>수강생도 값이 온다</b>(2026-08-23, #330 —
     * 전엔 비강사면 항상 빈 배열). 없으면 빈 배열. ⚠️ {@code certs.length > 0} 을 "강사인가" 로 읽지 말 것 —
     * 그건 {@code roles} 다.
     */
    private List<CertBadge> certs;

    @Getter
    @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class CertBadge {
        /** 자격이 속한 종목 코드(예 "FREEDIVING"). */
        private String disciplineCode;
        /** 발급 단체 코드(Sanity 카탈로그, 예 "AIDA"·"PADI"·"OTHER"). */
        private String organizationCode;
        /** organizationCode 가 "OTHER" 일 때 직접입력 단체명(아니면 null). */
        private String organizationOther;
        /** 평탄화 레벨 — 그룹 내 최고. */
        private CertLevel level;
        /** 검증됨({@code verification.status == VERIFIED}). 레벨에서 추론하지 않는다 — FE 가 검증마크/중립 칩을 가른다. */
        private boolean verified;
    }
}
