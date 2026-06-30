package com.diving.pungdong.profile.dto;

import com.diving.pungdong.account.Role;
import lombok.*;

import java.util.List;
import java.util.Set;

/**
 * 마이페이지 프로필 카드 — 본인({@code @CurrentUser}) 통합 조회. 기존 {@code AccountBasicInfo}(id/email/nickName/
 * roles)에 프로필 사진 + 자격 뱃지를 더한다. account 기본정보 ⊕ instructorapplication 의 승인 자격을 합성한 응답.
 *
 * <p>career(경력)·rating(평점)은 데이터 모델 부재로 <b>이번 범위 제외</b> — rating 은 V2 Course 리뷰 평균으로 신설
 * 예정, career 는 보류. 자격 {@code level/ratingCode} 도 아직 미저장이라 뺀다(추후 ApplicationCertificate 확장).
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
    /** 자격 뱃지 — 승인된(APPROVED) 강사 신청의 자격증들. 비강사는 빈 배열. */
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
    }
}
