package com.diving.pungdong.usecase;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.Role;
import com.diving.pungdong.address.AddressApiClient;
import com.diving.pungdong.address.StubAddressApiClient;
import com.diving.pungdong.global.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주소(address) 도메인 use-case — 주소 검색 + 좌표 변환. 실 H2 + 시큐리티 체인 + 외부 경계
 * {@link AddressApiClient} 를 <b>@MockBean 으로 교체</b>해 결정적으로 만든다.
 *
 * <p>왜 @MockBean 인가: 기본 {@link StubAddressApiClient}(고정값)에 의존하면 셸 env 의
 * {@code ADDRESS_GEOCODE_MODE=juso} 가 새어들어와 {@code JusoAddressApiClient}(실 juso 호출)가
 * 활성화되면서 테스트가 외부 API·네트워크에 의존해 깨졌다(응답 건수/좌표가 stub 기대값과 불일치).
 * 외부 third-party HTTP 경계는 테스트에서 mock 한다는 컨벤션대로, env·profile 과 무관하게 stub 픽스처를
 * 강제한다(여기선 {@link StubAddressApiClient} 의 고정값에 위임 — 단일 출처).
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

    @MockBean AddressApiClient addressApiClient;

    /** mock 을 stub 의 고정 픽스처에 위임 — env(geocode-mode)와 무관하게 결정적. */
    @BeforeEach
    void wireStubFixtures() {
        AddressApiClient stub = new StubAddressApiClient();
        given(addressApiClient.search(anyString(), anyInt(), anyInt()))
                .willAnswer(inv -> stub.search(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        given(addressApiClient.geocode(any()))
                .willAnswer(inv -> stub.geocode(inv.getArgument(0)));
    }

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
