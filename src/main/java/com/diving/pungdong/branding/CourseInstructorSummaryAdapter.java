package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.course.InstructorSummaryProvider;
import com.diving.pungdong.course.dto.CourseInstructorResponse;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 강의 상세용 강사 요약 합성 — {@code course} 가 선언한 {@link InstructorSummaryProvider} 의 구현.
 *
 * <p><b>왜 이 패키지인가</b>: 요약이 네 도메인(account · instructorapplication · branding · course)에
 * 걸치는데 {@code branding} 만 그 넷을 모두 참조할 수 있다. {@code course} 가 {@code branding} 을
 * import 하면 패키지가 서로를 참조하게 된다 — 근거는 {@link InstructorSummaryProvider} 의 javadoc.
 *
 * <p><b>브랜딩 프로필이 없어도 요약은 나온다.</b> 프로필 행이 소유하는 건 tagline·bio 뿐이고, 나머지는
 * 전부 파생값이다. 그래서 프로필을 만든 적 없는 강사는 그 둘만 null 이다 — 아바타·인증마크·자격이
 * 함께 사라지지 않는다.
 *
 * <p><b>대신 그 둘은 프로필의 공개 설정을 따른다</b>(2026-08-22). 유저가 프로필을 비공개로 내리면
 * ({@code isPublished=false}) tagline·bio 는 여기서도 빠지고, 나머지는 그대로 나간다.
 * <b>왜</b>: 이 레포가 정의한 비공개의 뜻은 <b>"내 포트폴리오를 감춘다"</b> 이고
 * ({@code community.CommunityPostSpecifications.feedVisible} 의 주석 — 커뮤니티 글이 함께 사라지지
 * 않는 근거이기도 하다), tagline·bio 는 <b>그 포트폴리오 페이지의 본문 그 자체</b>다. 반면 닉네임·아바타는
 * 계정 사실이고 인증마크·자격은 강사 신청 소유라 감출 대상이 아니다.
 * 즉 규칙은 한 문장이다 — <b>값의 소유자가 그 값의 거동을 정한다.</b> 이 클래스가 존재하는 근거
 * (아바타·자격이 브랜딩 게이트에 걸리면 안 된다)와 같은 원칙을 반대 방향으로 적용한 것뿐이다.
 * ⚠️ 강의 카드에 <b>항상</b> 소개 문구가 필요해지면 프로필 본문을 빌려오지 말고
 * <b>코스가 소유하는 필드</b>를 새로 둘 것.
 *
 * <p>⚠️ 강의 수 규칙({@code showSeededCourses} 게이팅)은 {@link BrandingService} 의
 * {@code products.lessons} · {@code community.CommunityAuthorComposer} 의 칩과 <b>같아야 한다</b> —
 * 갈리면 같은 강사의 "강의 N개" 가 화면마다 달라진다. 셋이 같은 규칙을 각자 적고 있어 한 곳으로
 * 모으는 건 후속 과제다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseInstructorSummaryAdapter implements InstructorSummaryProvider {

    private final AccountBrandingJpaRepo brandingRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final CourseJpaRepo courseRepo;
    private final SiteSettingsProvider siteSettings;

    @Override
    public CourseInstructorResponse summarize(Account instructor) {
        if (instructor == null) {
            return null;
        }

        List<InstructorApplication> approved = applicationRepo
                .findByAccountIdAndStatus(instructor.getId(), InstructorApplicationStatus.APPROVED);
        boolean isInstructor = !approved.isEmpty();

        // 미작성이면 tagline·bio 만 비는 게 맞다 — 여기서 만들지 않는다(조회는 생성하지 않는다).
        AccountBranding branding = brandingRepo.findByAccountId(instructor.getId()).orElse(null);
        // 유저가 프로필을 비공개로 내렸으면 그 둘은 싣지 않는다(포트폴리오 본문이라서 — 클래스 javadoc).
        AccountBranding visible = branding != null && branding.isPublished() ? branding : null;

        return CourseInstructorResponse.builder()
                .nickName(instructor.getNickName())
                .avatarUrl(ProfilePhoto.displayUrlOf(instructor))
                .instructor(isInstructor)
                .tagline(visible == null ? null : visible.getTagline())
                .bio(visible == null ? null : visible.getBio())
                .certs(isInstructor ? certBadgesOf(approved) : null)
                .lessonCount(isInstructor ? lessonCountOf(instructor.getId()) : null)
                .build();
    }

    private List<CourseInstructorResponse.CertBadge> certBadgesOf(List<InstructorApplication> approved) {
        return approved.stream()
                .flatMap(application -> application.getCertificates().stream()
                        .map(cert -> CourseInstructorResponse.CertBadge.builder()
                                .disciplineCode(application.getDisciplineCode())
                                .organizationCode(cert.getOrganizationCode())
                                .organizationOther(cert.getOrganizationOther())
                                .build()))
                .collect(Collectors.toList());
    }

    private Integer lessonCountOf(Long instructorId) {
        long count = siteSettings.current().showSeededCourses()
                ? courseRepo.countByInstructorIdAndStatus(instructorId, CourseStatus.OPEN)
                : courseRepo.countByInstructorIdAndStatusAndSeededFalse(instructorId, CourseStatus.OPEN);
        return (int) count;
    }
}
