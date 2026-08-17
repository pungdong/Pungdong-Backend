package com.diving.pungdong.usecase;

import com.diving.pungdong.ota.OtaDeviceJpaRepo;
import com.diving.pungdong.ota.OtaTelemetryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * permitAll 텔레메트리의 <b>신규 설치 생성 상한</b>(IP 단위) 스펙.
 *
 * <p>별도 클래스인 이유: 상한은 임베디드 Redis 카운터를 쓰는데 그건 테스트 클래스들이 공유한다. 기본
 * 프로파일에서 켜두면 다른 클래스가 만든 기기까지 카운터에 쌓여 <b>실행 순서에 따라 엉뚱한 테스트가 429</b>
 * 를 받는다. 그래서 기본은 꺼두고(application-test.yml) 여기서만 아주 작은 값으로 켠다
 * ({@code identity-verification} 의 send-cooldown 과 같은 방식).
 *
 * <p>핵심 성질은 <b>fail-open</b> 이다 — 이 가드는 남용만 막고, 판정이 애매하면(IP 미상·Redis 장애)
 * 그냥 통과시킨다. 텔레메트리를 조금 더 받는 것보다 정상 기기를 막는 쪽이 훨씬 나쁘기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "pungdong.ota.new-install-ip-quota-enabled=true",
        "pungdong.ota.new-install-ip-quota=2"
})
class OtaTelemetryRateLimitUseCaseTest {

    private static final String DEVICES = "/app/ota/devices";

    @Autowired MockMvc mockMvc;
    @Autowired OtaDeviceJpaRepo otaDeviceJpaRepo;
    @Autowired RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void cleanUp() {
        otaDeviceJpaRepo.deleteAll();
        Set<String> keys = redisTemplate.keys(OtaTelemetryService.IP_QUOTA_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("L1: 한 IP 가 상한을 넘겨 새 설치를 만들면 429 + 얼마나 기다려야 하는지 알려준다")
    void newInstallFlood_isRateLimited() throws Exception {
        register("flood-1");
        register("flood-2");

        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                        .content(body("flood-3")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    @DisplayName("L2: 상한에 걸려도 이미 등록된 기기의 보고는 계속 받는다 (정상 사용자를 막지 않는다)")
    void existingDevice_isNotBlockedByQuota() throws Exception {
        register("known-1");
        register("known-2");
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON).content(body("known-3")))
                .andExpect(status().isTooManyRequests());

        // 상한은 '새 설치 생성'에만 걸린다 — 기존 기기의 재보고는 그대로 200.
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON).content(body("known-1")))
                .andExpect(status().isOk());
    }

    private void register(String installId) throws Exception {
        mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON).content(body(installId)))
                .andExpect(status().isOk());
    }

    private static String body(String installId) {
        return "{\"installId\":\"" + installId + "\",\"platform\":\"IOS\",\"appVersion\":\"1.0.0\"}";
    }
}
