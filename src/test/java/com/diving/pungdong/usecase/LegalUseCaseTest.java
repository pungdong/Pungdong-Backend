package com.diving.pungdong.usecase;

import com.diving.pungdong.legal.LegalDocumentClient;
import com.diving.pungdong.legal.dto.LegalDocumentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 법적 고지 프록시 use-case — {@code @DisplayName} 을 위→아래로 읽으면 스펙.
 *
 * <p>{@code GET /legal/{slug}} 는 BE 가 Sanity {@code legalDocument} 를 read 토큰으로 서버사이드
 * 조회해 공개 제공한다(인증 불필요). 외부 경계 {@link LegalDocumentClient} 만 {@code @MockBean} —
 * 실제 Sanity HTTP 는 진짜 경계라 mock OK.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegalUseCaseTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private LegalDocumentClient legalDocumentClient;

    @Test
    @DisplayName("L1 인증 없이 GET /legal/terms → 200, slug/title/version/body 반환 (permitAll + 프록시)")
    void publicGetTerms() throws Exception {
        ArrayNode body = objectMapper.createArrayNode();
        body.add(objectMapper.createObjectNode().put("_type", "block").put("style", "normal"));
        given(legalDocumentClient.fetch(eq("terms")))
                .willReturn(Optional.of(new LegalDocumentResponse("terms", "이용약관", body, "1.0", "2026-06-26")));

        mockMvc.perform(get("/legal/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("terms"))
                .andExpect(jsonPath("$.title").value("이용약관"))
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.body").isArray());
    }

    @Test
    @DisplayName("L2 활성 문서 없음/허용 외 slug → 404 (client empty)")
    void notFoundWhenEmpty() throws Exception {
        given(legalDocumentClient.fetch(eq("unknown"))).willReturn(Optional.empty());

        mockMvc.perform(get("/legal/unknown")).andExpect(status().isNotFound());
    }
}
