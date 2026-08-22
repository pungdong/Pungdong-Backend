package com.diving.pungdong.certificate;

import com.diving.pungdong.certificate.dto.CertificateBadge;
import com.diving.pungdong.course.CertLevel;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <b>사람 표면</b>(마이페이지·공개 프로필·커뮤니티 작성자 칩)의 자격 뱃지 표시 규칙 — 단일 출처.
 * 정책 원문은 {@code docs/features/instructor-onboarding.md §자격증 검증 — 표시 규칙}(2026-08-23, #330).
 *
 * <pre>
 *   수강생 레벨(LEVEL_1~4)은 자기신고 그대로, 강사 레벨(INSTRUCTOR·INSTRUCTOR_TRAINER)은 VERIFIED 만.
 *   그 집합에서 (종목, 단체) 그룹당 가장 높은 레벨 1장. 레벨 내림차순 정렬.
 * </pre>
 *
 * <p><b>왜 자기신고를 보여주나</b>: 인증 뱃지가 강사 전용이면 수강생이 자격증을 등록할 이유가 없다(딴 AIDA2 가
 * 어디에도 안 나온다). 자격증은 "다음 레벨을 노리게 만드는" 장치이기도 하다. 동시에 강사·강사 트레이너는
 * 아무나 주장하면 안 된다 — 그건 검증 후에만. 부수 효과: 강사 신청이 심사 중이라 아직 강사 뱃지를 못 다는
 * 사람도 자기 수강생 레벨 뱃지는 그대로 보이고, 승인되는 순간 뱃지가 위로 올라간다(사라졌다 나타나는 구간이 없다).
 *
 * <p>🔴 <b>이 파생을 강사 자격 표면에 돌려쓰면 안 된다.</b> 강의 상세의 강사 인셋({@code CourseDetailInstructor.certs})과
 * 강사 browse 필터({@code organizationCodes})는 <b>VERIFIED 강사 자격만</b>
 * ({@link StudentCertificateService#verifiedBadgesOf} · 레포 JPQL). 자기신고가 섞이면 "이 강사 자격 있음"의 뜻이
 * 흐려지고, 자기신고로 강사 검색에 걸린다.
 *
 * <p><b>정렬이 계약의 일부다.</b> 커뮤니티 칩처럼 "1장만" 쓰는 표면이 {@code [0]} 으로 자를 수 있어야 한다 —
 * 없으면 표면마다 최고 레벨 계산을 복제하게 된다.
 */
public final class CertificateBadgePolicy {

    private CertificateBadgePolicy() {
    }

    /** 표시 후보인가 — 수강생 레벨은 무조건, 강사 레벨은 VERIFIED 만. */
    static boolean isDisplayable(StudentCertificate c) {
        CertLevel level = c.getLevel();
        if (level == null) {
            return false;
        }
        return !level.isInstructorLevel()
                || c.getVerification().is(CertificateVerificationStatus.VERIFIED);
    }

    /**
     * 한 사람의 자격증 전부 → 표시 뱃지. (종목, 단체)별 최고 레벨 1장, 레벨 내림차순(같으면 종목·단체 코드순).
     * 입력은 한 계정의 행이어야 한다(섞이면 남의 자격이 그룹에 합쳐진다).
     */
    public static List<CertificateBadge> displayBadges(Collection<StudentCertificate> certificates) {
        Map<String, StudentCertificate> topPerGroup = new LinkedHashMap<>();
        for (StudentCertificate c : certificates) {
            if (!isDisplayable(c)) {
                continue;
            }
            // 레벨 사다리 = CertLevel 선언 순서(compareTo). 동률이면 먼저 본 행(id 순)을 유지한다.
            topPerGroup.merge(groupKey(c), c,
                    (current, candidate) -> candidate.getLevel().compareTo(current.getLevel()) > 0 ? candidate : current);
        }
        return topPerGroup.values().stream()
                .sorted(Comparator.comparing(StudentCertificate::getLevel).reversed()
                        .thenComparing(StudentCertificate::getDisciplineCode)
                        .thenComparing(StudentCertificate::getOrganizationCode))
                .map(CertificateBadge::of)
                .collect(Collectors.toList());
    }

    private static String groupKey(StudentCertificate c) {
        return c.getDisciplineCode() + " " + c.getOrganizationCode();
    }
}
