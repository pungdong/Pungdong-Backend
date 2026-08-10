package com.diving.pungdong.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    // 이니시스 인증결과 콜백(P_NEXT_URL) 경로 — permitAll 매처와 CORS 제외 등록이 같은 값을 쓰도록 단일 출처.
    private static final String INICIS_CALLBACK_PATH = "/payments/inicis/return";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .httpBasic(httpBasic -> httpBasic.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .antMatchers("/sign/sign-up", "/sign/login", "/sign/check/**", "/sign/refresh",
                                "/email/code/**").permitAll()
                        .antMatchers("/lecture/detail", "/lecture/list", "/lecture/new/list", "/lecture/popular/list",
                                "/lecture/list/search/**", "/lecture/instructor/info/creator", "/lecture/*/like").permitAll()
                        .antMatchers(HttpMethod.GET, "/lecture", "/location", "/review/list", "/equipment/list").permitAll()
                        .antMatchers(HttpMethod.GET, "/schedule", "/schedule/equipments").permitAll()
                        .antMatchers(HttpMethod.PATCH, "/account/deleted-state").permitAll()
                        .antMatchers(HttpMethod.PUT, "/account/forgot-password").permitAll()
                        .antMatchers("/lectureImage/list").permitAll()
                        .antMatchers(HttpMethod.GET, "/exception/**").permitAll()
                        .antMatchers(HttpMethod.GET, "/disciplines").permitAll()
                        .antMatchers(HttpMethod.GET, "/instructors/public").permitAll()
                        // 공개 브랜딩 페이지 — 위 리터럴보다 반드시 뒤에 온다(그래야 /instructors/public 이 목록으로 간다).
                        // ⚠️ ant 의 '*' 는 '/' 를 넘지 않으므로 하위 경로(.../posts 등)는 매처를 따로 추가해야 한다.
                        .antMatchers(HttpMethod.GET, "/instructors/*").permitAll()
                        .antMatchers(HttpMethod.GET, "/instructors/*/posts").permitAll()
                        .antMatchers(HttpMethod.GET, "/branding-posts/*").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/browse").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/level-labels").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/*/detail").permitAll()
                        .antMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .antMatchers(HttpMethod.GET, "/legal/**").permitAll()
                        .antMatchers(HttpMethod.POST, "/webhooks/sanity/venue").permitAll()
                        // 이니시스 결제창이 인증결과를 form POST 하는 콜백 — 콜백엔 우리 JWT 가 없어 permitAll.
                        // 인증은 P_AUTH_TID(우리 콜백에만 옴)가 대신하고, 승인 실패 시 fail 로 리다이렉트한다. /payments/** 보다 먼저.
                        .antMatchers(HttpMethod.POST, INICIS_CALLBACK_PATH).permitAll()
                        .antMatchers("/admin/instructor-applications/**").hasRole("ADMIN")
                        .antMatchers("/instructor-applications/**").authenticated()
                        .antMatchers("/identity-verifications/**").authenticated()
                        .antMatchers("/consents/**").authenticated()
                        .antMatchers("/venues/**").authenticated()
                        .antMatchers("/venue-equipment/**").authenticated()
                        .antMatchers("/venue-favorites/**").authenticated()
                        .antMatchers("/instructor/availability/**").authenticated()
                        .antMatchers("/instructor/enrollments/**").authenticated()
                        .antMatchers("/enrollments/**").authenticated()
                        .antMatchers("/payments/**").authenticated()
                        // 브랜딩 오너 편집 — role 이 아니라 인증. 일반 유저도 쓰고(D2), 강사도 승인 전에 편집 화면이 있다.
                        .antMatchers("/branding/**").authenticated()
                        .antMatchers("/branding-images").authenticated()
                        .antMatchers("/course-images").authenticated()
                        .antMatchers("/courses/**").authenticated()
                        .antMatchers("/address-search", "/geocode").authenticated()
                        .antMatchers("/account/instructor/**").hasRole("INSTRUCTOR")
                        .antMatchers("/lecture/create", "/lecture/update", "/lecture/delete", "/lecture/manage/list",
                                "/location/create", "/lectureImage/create/list", "/equipment/create/list").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .accessDeniedHandler(accessDeniedHandler)
                        .authenticationEntryPoint(authenticationEntryPoint)
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, redisTemplate),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 이니시스 인증결과 콜백(P_NEXT_URL)은 결제창(paypro.inicis.com)·앱 WebView 가 cross-origin form POST 로 들어온다.
        // 이 form POST(navigation)는 브라우저 JS(fetch/XHR)가 아니라 CORS 의 대상이 아니고, 콜백 진위는 P_AUTH_TID +
        // 서버사이드 승인(주문 권위 금액)으로 보장한다 — CORS 는 이 경로의 보안 경계가 아니다. 그래서 전역 allowlist 로
        // 거부하지 않도록 이 경로만 origin 을 연다. credential(JWT/쿠키)은 쓰지 않으므로 allowCredentials=false.
        // UrlBasedCorsConfigurationSource 는 등록 순서대로 첫 매치를 쓰므로 /** 보다 먼저 등록해야 한다.
        CorsConfiguration callbackConfig = new CorsConfiguration();
        callbackConfig.addAllowedOriginPattern("*");
        callbackConfig.setAllowedMethods(List.of("POST", "OPTIONS"));
        callbackConfig.setAllowedHeaders(List.of("*"));
        callbackConfig.setAllowCredentials(false);
        callbackConfig.setMaxAge(3600L);
        source.registerCorsConfiguration(INICIS_CALLBACK_PATH, callbackConfig);

        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring()
                .requestMatchers(PathRequest.toStaticResources().atCommonLocations())
                .antMatchers("/docs/**", "/webjars/**", "/local-uploads/**");
    }
}
