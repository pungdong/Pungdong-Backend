package com.diving.pungdong.global.config;

import com.diving.pungdong.global.sitesettings.SiteSettings;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 테스트용 {@link SiteSettingsProvider} — 실 {@code HttpSanitySiteSettingsProvider}({@code @Profile("!test")})
 * 가 테스트에서 Sanity 로 네트워크 호출하는 것을 막고, 고정값을 준다 ({@code @Profile("test")} 스캐폴드).
 *
 * <p>기본값 = <b>런칭됨 + 데모 노출</b>(launched=true, showSeededCourses=true) — 대부분의 use-case 테스트는
 * "정상 운영" 가정이라 신청/둘러보기가 그대로 동작한다. 런칭 전/데모 가림을 검증하는 테스트는 이 bean 을
 * {@code @MockBean} 으로 덮어 플래그를 직접 제어한다.
 */
@Configuration
@Profile("test")
public class TestSiteSettingsConfig {

    @Bean
    public SiteSettingsProvider siteSettingsProvider() {
        return () -> new SiteSettings(true, true);
    }
}
