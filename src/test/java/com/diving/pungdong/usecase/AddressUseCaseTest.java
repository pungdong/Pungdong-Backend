package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주소(address) 도메인 use-case — 주소 검색 + 좌표 변환. 실 H2 + 시큐리티 체인 + <b>stub</b> 클라이언트
 * (좌표제공 개발키 부재로 로컬 기본 stub). 외부 juso 는 호출하지 않으므로 결정적.
 *
 * <p>{@code @DisplayName} 을 위→아래로 읽으면 사양. S* 성공 / V* 검증 / T* 인증.
 * ⚠️ Authorization 헤더는 raw JWT(Bearer prefix 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddressUseCaseTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AccountJpaRepo accountRepo;

    @AfterEach
    void cleanUp() {
        accountRepo.deleteAll();
    }

    private String token() {
        Account a = accountRepo.save(Account.builder()
                .email("u@pungdong.com").password("x").nickName("유저")
                .roles(new HashSet<>(Set.of(Role.STUDENT))).build());
        return jwtTokenProvider.createAccessToken(String.valueOf(a.getId()), a.getRoles());
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("S1 도로명주소 검색 → stub 후보 목록(서울시청)이 좌표키와 함께 반환된다")
    void s1_search() throws Exception {
        mockMvc.perform(get("/address-search?keyword=세종대로 110")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].roadAddr").value("서울특별시 중구 세종대로 110 (태평로1가)"))
                .andExpect(jsonPath("$.items[0].admCd").value("1114010300"))
                .andExpect(jsonPath("$.items[0].zipNo").value("04524"));
    }

    @Test
    @DisplayName("S2 선택한 주소 → 좌표 변환(stub 고정 WGS84 위경도)")
    void s2_geocode() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("admCd", "1114010300");
        body.put("rnMgtSn", "111103000015");
        body.put("udrtYn", "0");
        body.put("buldMnnm", "110");
        body.put("buldSlno", "0");

        mockMvc.perform(post("/geocode")
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(37.566535))
                .andExpect(jsonPath("$.longitude").value(126.977969));
    }

    @Test
    @DisplayName("V1 검색어가 비면 400")
    void v1_blank_keyword() throws Exception {
        mockMvc.perform(get("/address-search?keyword=")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V2 좌표 변환 필수 필드(admCd 등)가 없으면 400")
    void v2_geocode_missing_field() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("admCd", "1114010300"); // rnMgtSn/udrtYn/buldMnnm 누락

        mockMvc.perform(post("/geocode")
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T1 인증 없이 호출하면 401")
    void t1_unauthenticated() throws Exception {
        mockMvc.perform(get("/address-search?keyword=세종대로"))
                .andExpect(status().isUnauthorized());
    }
}
