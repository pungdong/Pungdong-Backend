package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.diving.pungdong.certificate.CertificateSource;
import com.diving.pungdong.certificate.CertificateVerification;
import com.diving.pungdong.certificate.CertificateVerificationKind;
import com.diving.pungdong.certificate.CertificateVerificationStatus;
import com.diving.pungdong.certificate.StudentCertificate;
import com.diving.pungdong.certificate.StudentCertificateJpaRepo;
import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마이페이지 프로필 use-case — GET /account/profile (인증·본인). 실 H2 + 시큐리티 체인.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래 = 사양. A* = 프로필 조회. 기본정보 + 프로필 사진 +
 * 승인(APPROVED) 자격 뱃지를 합성해 내려준다. 비강사는 certs 빈 배열, 미승인 자격은 빠진다. ⚠️ Authorization raw JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountProfileUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired InstructorApplicationJpaRepo applicationRepo;
    @Autowired StudentCertificateJpaRepo certificateRepo;

    @AfterEach
    void cleanUp() {
        certificateRepo.deleteAll();
        applicationRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    /* ─── fixtures ─── */

    private Account account(String email, String nick, String photoUrl, Role... roles) {
        Account account = Account.builder()
                .email(email).password("x").nickName(nick)
                .roles(new HashSet<>(Set.of(roles))).build();
        if (photoUrl != null) {
            account.setProfilePhoto(profilePhotoRepo.save(ProfilePhoto.builder().imageUrl(photoUrl).build()));
        }
        return accountRepo.save(account);
    }

    private String token(Account a) {
        return jwt.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private void applicationWithCert(Account account, String disciplineCode, String orgCode,
                                     InstructorApplicationStatus status) {
        InstructorApplication app = InstructorApplication.builder()
                .account(account).disciplineCode(disciplineCode).status(status)
                .submittedAt(OffsetDateTime.now(ZoneOffset.UTC)).createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build();
        applicationRepo.save(app);
        if (status == InstructorApplicationStatus.APPROVED) {
            verifiedCertificate(account, disciplineCode, orgCode);
        }
    }

    /** VERIFIED 강사레벨 자격증 1장 — 인증마크(certs·organizationCodes)의 출처(2026-08-22 수렴: 승인 신청 첨부 → VERIFIED 자격증). */
    private StudentCertificate verifiedCertificate(Account owner, String disciplineCode, String organizationCode) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return certificateRepo.save(StudentCertificate.builder()
                .owner(owner).disciplineCode(disciplineCode).organizationCode(organizationCode)
                .organizationName(organizationCode).level(CertLevel.INSTRUCTOR)
                .certificateNumber("INS-1").acquiredAt(java.time.LocalDate.of(2020, 1, 1))
                .source(CertificateSource.EXTERNAL).photoFileKey("studentCertificate/" + owner.getId() + "/x.jpg")
                .createdAt(now)
                .verification(new CertificateVerification(CertificateVerificationStatus.VERIFIED,
                        CertificateVerificationKind.APPLICATION, null, now, now))
                .build());
    }


    /* ─── A* 프로필 조회 ─── */

    @Test
    @DisplayName("A1 강사 본인 프로필 — 기본정보·프로필사진·승인 자격 뱃지(종목·단체)를 합성해 내려준다")
    void instructorProfileWithCerts() throws Exception {
        Account ins = account("ins-a1@pd.com", "강사A1", "https://cdn/a1.png", Role.STUDENT, Role.INSTRUCTOR);
        applicationWithCert(ins, "FREEDIVING", "AIDA", InstructorApplicationStatus.APPROVED);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ins.getId()))
                .andExpect(jsonPath("$.email").value("ins-a1@pd.com"))
                .andExpect(jsonPath("$.nickName").value("강사A1"))
                .andExpect(jsonPath("$.profilePhotoUrl").value("https://cdn/a1.png"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("STUDENT", "INSTRUCTOR")))
                .andExpect(jsonPath("$.certs", hasSize(1)))
                .andExpect(jsonPath("$.certs[0].disciplineCode").value("FREEDIVING"))
                .andExpect(jsonPath("$.certs[0].organizationCode").value("AIDA"));
    }

    @Test
    @DisplayName("A2 순수 학생 프로필 — certs 는 빈 배열(강사 자격 없음)")
    void studentProfileHasNoCerts() throws Exception {
        Account stu = account("stu-a2@pd.com", "학생A2", null, Role.STUDENT);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(stu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickName").value("학생A2"))
                .andExpect(jsonPath("$.profilePhotoUrl").doesNotExist()) // 사진 미설정 → null
                .andExpect(jsonPath("$.certs", hasSize(0)));
    }

    @Test
    @DisplayName("A3 미승인(SUBMITTED) 신청의 자격은 프로필에 안 나온다(승인된 것만 뱃지)")
    void onlyApprovedCertsShown() throws Exception {
        Account ins = account("ins-a3@pd.com", "검수중A3", null, Role.STUDENT);
        applicationWithCert(ins, "FREEDIVING", "AIDA", InstructorApplicationStatus.SUBMITTED);

        mockMvc.perform(get("/account/profile").header(HttpHeaders.AUTHORIZATION, token(ins)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certs", hasSize(0)));
    }

    @Test
    @DisplayName("A4 비로그인은 프로필을 못 본다(401)")
    void anonymousRejected() throws Exception {
        mockMvc.perform(get("/account/profile"))
                .andExpect(status().isUnauthorized());
    }
}
