package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhotoJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.consent.AgreementTermArchive;
import com.diving.pungdong.consent.AgreementTermArchiveJpaRepo;
import com.diving.pungdong.consent.ConsentJpaRepo;
import com.diving.pungdong.consent.SanityTermClient;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동의(consent) 도메인 use-case 시나리오.
 *
 * <p>설계: 약관 전문은 유저별로 복사하지 않고 버전당 1행 {@link AgreementTermArchive} 에 박제하고
 * {@link com.diving.pungdong.consent.Consent} 는 그 행을 참조한다. 처음 보는 (key,version) 만
 * Sanity 에서 전문을 받아 freeze, 이후는 재사용. 전문 소스(Sanity)는 외부 경계라 {@link SanityTermClient}
 * 만 {@code @MockBean} — 나머지는 실제 H2 + 시큐리티 체인.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 위→아래로 읽으면 사양. C* = consent 본체, V* = 검증 거부.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsentUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;
    @Autowired ProfilePhotoJpaRepo profilePhotoRepo;
    @Autowired ConsentJpaRepo consentRepo;
    @Autowired AgreementTermArchiveJpaRepo archiveRepo;

    @MockBean SanityTermClient sanityTermClient;

    @AfterEach
    void cleanUp() {
        consentRepo.deleteAll();   // FK → account, agreement_term
        archiveRepo.deleteAll();
        accountRepo.deleteAll();
        profilePhotoRepo.deleteAll();
    }

    private Account createStudent(String email, String nick) {
        return accountRepo.save(Account.builder()
                .email(email).password("encoded").nickName(nick)
                .roles(new HashSet<>(Set.of(Role.STUDENT)))
                .build());
    }

    private String tokenFor(Account a) {
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    /** Sanity 의 그 key 현재 약관이 (version, 전문) 이도록 stub. 동의에 기록될 version 의 권위 출처. */
    private void stubCurrent(String key, String version, String title, String body) {
        when(sanityTermClient.fetchCurrentTerm(key))
                .thenReturn(Optional.of(new SanityTermClient.FetchedTerm(key, version, title, body, true)));
    }

    /** context + 약관 key 들로 POST body 생성 (version 은 요청에 없음 — BE 가 정함). */
    private String body(String context, String... keys) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("context", context);
        root.put("keys", Arrays.asList(keys));
        return objectMapper.writeValueAsString(root);
    }

    @Test
    @DisplayName("C1: 강사신청 화면에서 약관 2건에 동의하면 201 + 각 버전이 처음이라 박제되고 동의 이력 2건이 남는다")
    void record_firstTime_freezesArchive() throws Exception {
        Account student = createStudent("c1@test.com", "diverC1");
        stubCurrent("privacy_collect", "v1", "개인정보 수집·이용 동의", "[{\"_type\":\"block\"}]");
        stubCurrent("unique_id_ci_di", "v1", "고유식별정보 처리 동의", "[{\"_type\":\"block\"}]");

        mockMvc.perform(post("/consents")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("instructor_application", "privacy_collect", "unique_id_ci_di")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recorded").value(2))
                .andExpect(jsonPath("$.agreements[0].version").value("v1")); // BE 가 정한 버전을 응답

        assertThat(archiveRepo.findAll()).hasSize(2);          // 버전당 1행 박제
        assertThat(consentRepo.findAll()).hasSize(2);          // 동의 이력 2건
        AgreementTermArchive frozen = archiveRepo.findByTermKeyAndVersion("privacy_collect", "v1").orElseThrow();
        assertThat(frozen.getTitle()).isEqualTo("개인정보 수집·이용 동의");
        assertThat(frozen.getBody()).isEqualTo("[{\"_type\":\"block\"}]");   // 전문 박제됨
        assertThat(frozen.isRequired()).isTrue();
    }

    @Test
    @DisplayName("C2: 다른 계정이 같은 약관 현재 버전에 동의하면 박제는 재사용되고(1행) 이력만 계정마다 쌓인다")
    void record_sameVersion_reusesArchive() throws Exception {
        Account a = createStudent("c2a@test.com", "diverC2a");
        Account b = createStudent("c2b@test.com", "diverC2b");
        stubCurrent("privacy_collect", "v1", "개인정보 수집·이용 동의", "[]");

        mockMvc.perform(post("/consents").header(HttpHeaders.AUTHORIZATION, tokenFor(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("signup", "privacy_collect")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/consents").header(HttpHeaders.AUTHORIZATION, tokenFor(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("signup", "privacy_collect")))
                .andExpect(status().isCreated());

        assertThat(archiveRepo.findAll()).hasSize(1);          // 재사용 — 전문은 1번만 저장
        assertThat(consentRepo.findAll()).hasSize(2);          // 이력은 계정마다
        // 현재 버전은 동의마다 Sanity 에서 권위 조회 (박제 재사용과 무관)
        verify(sanityTermClient, times(2)).fetchCurrentTerm("privacy_collect");
    }

    @Test
    @DisplayName("C3: 약관이 개정되어 현재 버전이 오르면(v1→v2) 새 버전으로 별도 박제된다 (개정 = 새 행)")
    void record_newVersion_freezesSeparately() throws Exception {
        Account student = createStudent("c3@test.com", "diverC3");

        stubCurrent("privacy_collect", "v1", "개인정보 동의 v1", "[]");
        mockMvc.perform(post("/consents").header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("signup", "privacy_collect")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agreements[0].version").value("v1"));

        stubCurrent("privacy_collect", "v2", "개인정보 동의 v2", "[]");  // Sanity 에서 개정 + version bump
        mockMvc.perform(post("/consents").header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("signup", "privacy_collect")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agreements[0].version").value("v2")); // BE 가 현재 버전(v2) 기록

        assertThat(archiveRepo.findAll()).hasSize(2);          // v1, v2 각각 박제
        assertThat(consentRepo.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("V1: Sanity 에 활성 약관이 없는 key 에 동의하면 400 이고 박제·이력 모두 남지 않는다")
    void record_unknownTerm_rejected() throws Exception {
        Account student = createStudent("v1@test.com", "diverV1");
        // stub 없음 → Mockito 기본 Optional.empty()

        mockMvc.perform(post("/consents").header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("signup", "ghost_term")))
                .andExpect(status().isBadRequest());

        assertThat(archiveRepo.findAll()).isEmpty();
        assertThat(consentRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("V2: keys 가 비어 있으면 400 (적어도 1건 동의 필요)")
    void record_emptyAgreements_rejected() throws Exception {
        Account student = createStudent("v2@test.com", "diverV2");

        mockMvc.perform(post("/consents").header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("signup")))
                .andExpect(status().isBadRequest());

        assertThat(consentRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("C4: GET /consents/me 는 내 동의 이력을 최신순으로 (_embedded.consents) 돌려준다")
    void getMine_listsMyConsents() throws Exception {
        Account student = createStudent("c4@test.com", "diverC4");
        stubCurrent("privacy_collect", "v1", "개인정보 수집·이용 동의", "[]");

        mockMvc.perform(post("/consents").header(HttpHeaders.AUTHORIZATION, tokenFor(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("instructor_application", "privacy_collect")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/consents/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.consents[0].key").value("privacy_collect"))
                .andExpect(jsonPath("$._embedded.consents[0].version").value("v1"))
                .andExpect(jsonPath("$._embedded.consents[0].title").value("개인정보 수집·이용 동의"))
                .andExpect(jsonPath("$._embedded.consents[0].context").value("instructor_application"));
    }
}
