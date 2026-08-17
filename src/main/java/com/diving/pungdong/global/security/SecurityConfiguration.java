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
                        .antMatchers(HttpMethod.PATCH, "/account/deleted-state").permitAll()
                        .antMatchers(HttpMethod.PUT, "/account/forgot-password").permitAll()
                        .antMatchers(HttpMethod.GET, "/disciplines").permitAll()
                        .antMatchers(HttpMethod.GET, "/instructors/public").permitAll()
                        // 공개 브랜딩 페이지 — 위 리터럴보다 반드시 뒤에 온다(그래야 /instructors/public 이 목록으로 간다).
                        // ⚠️ ant 의 '*' 는 '/' 를 넘지 않으므로 하위 경로(.../posts 등)는 매처를 따로 추가해야 한다.
                        .antMatchers(HttpMethod.GET, "/instructors/*").permitAll()
                        .antMatchers(HttpMethod.GET, "/instructors/*/posts").permitAll()
                        .antMatchers(HttpMethod.GET, "/branding-posts/*").permitAll()
                        // 커뮤니티 읽기는 비로그인 허용. ant 의 `*` 는 `/` 를 넘지 않아 목록·상세를 따로 적는다.
                        // 쓰기(POST/PUT/DELETE/PATCH)는 아래 authenticated 매처가 잡는다.
                        .antMatchers(HttpMethod.GET, "/community/posts").permitAll()
                        .antMatchers(HttpMethod.GET, "/community/posts/*").permitAll()
                        .antMatchers(HttpMethod.GET, "/community/posts/*/related").permitAll()
                        .antMatchers(HttpMethod.GET, "/community/posts/*/comments").permitAll()
                        .antMatchers(HttpMethod.GET, "/community/categories").permitAll()
                        .antMatchers(HttpMethod.GET, "/community/tags/popular").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/browse").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/level-labels").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/*/detail").permitAll()
                        .antMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .antMatchers(HttpMethod.GET, "/legal/**").permitAll()
                        // 앱 최소버전 정책 — 비인증 조회. 🚨 이 경로는 어떤 경우에도 401 을 내면 안 된다:
                        // 앱이 공개 엔드포인트도 같은 axios 인스턴스(토큰 동봉)로 부르는데, 401 이 나오면 그
                        // 인터셉터가 setUnauthenticated() 를 호출해 부팅 중 정상 사용자가 강제 로그아웃된다.
                        .antMatchers(HttpMethod.GET, "/app/policy").permitAll()
                        // OTA 텔레메트리 — 로그인·푸시 권한과 무관하게 모든 설치를 세야 릴리스 대시보드가
                        // 의미를 갖는다(인증을 걸면 "번들에 갇혀 로그아웃한 사용자" 가 먼저 사라진다).
                        // 신분은 여전히 세션에서만 온다 — @CurrentUser 가 익명이면 null 이고 바디로 안 받는다.
                        // ⚠️ ant 의 '*' 는 '/' 를 넘지 않아 이벤트 경로를 따로 적어야 한다.
                        .antMatchers(HttpMethod.POST, "/app/ota/devices").permitAll()
                        .antMatchers(HttpMethod.POST, "/app/ota/devices/*/events").permitAll()
                        .antMatchers(HttpMethod.POST, "/webhooks/sanity/venue").permitAll()
                        // 이니시스 결제창이 인증결과를 form POST 하는 콜백 — 콜백엔 우리 JWT 가 없어 permitAll.
                        // 인증은 P_AUTH_TID(우리 콜백에만 옴)가 대신하고, 승인 실패 시 fail 로 리다이렉트한다. /payments/** 보다 먼저.
                        .antMatchers(HttpMethod.POST, INICIS_CALLBACK_PATH).permitAll()
                        .antMatchers("/admin/instructor-applications/**").hasRole("ADMIN")
                        // 신고 처리 큐 — 어드민 전용. /community/** 의 authenticated 매처보다 앞에 둬야
                        // ADMIN 검사가 실제로 걸린다(먼저 매치되는 매처가 이긴다).
                        .antMatchers("/admin/community/reports/**").hasRole("ADMIN")
                        // 결제 주문 수동 환불(운영 보정) — 어드민 전용. /payments/** 매처와 경로가 다르지만 명시.
                        .antMatchers("/admin/payments/**").hasRole("ADMIN")
                        // OTA 릴리스 대시보드(기기 카운트·드릴다운) + 앱 정책 편집 — 어드민 전용.
                        // 번들 메타·조작은 여기 없다(어드민이 Cloudflare D1 을 직접 읽고 쓴다).
                        .antMatchers("/admin/ota/**").hasRole("ADMIN")
                        .antMatchers("/admin/app/**").hasRole("ADMIN")
                        .antMatchers("/instructor-applications/**").authenticated()
                        // 학생 보유 자격증(프로필 > 내 자격증) — 강사도 개인 자격으로 쓰므로 hasRole 로 막지 않는다.
                        .antMatchers("/certificates/**").authenticated()
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
                        // 강사 전용이 아니다 — 일반 유저도 커뮤니티에 쓴다. hasRole 로 막으면 안 된다.
                        .antMatchers("/community/**").authenticated()
                        // 세션 단체 채팅. 강사·수강생이 같은 방을 쓰므로 여기서도 hasRole 로 가르지 않는다 —
                        // 실제 접근 판정(그 방의 참여자인가)은 서비스가 방마다 한다.
                        .antMatchers("/chat/**").authenticated()
                        .antMatchers("/branding-images").authenticated()
                        .antMatchers("/course-images").authenticated()
                        .antMatchers("/courses/**").authenticated()
                        .antMatchers("/address-search", "/geocode").authenticated()
                        .antMatchers("/account/instructor/**").hasRole("INSTRUCTOR")
                        // 레거시 v1 매처(/lecture·/location·/review/list·/equipment·/schedule·/lectureImage·/exception·
                        // /reservation)는 해당 컨트롤러와 함께 제거됨(2026-08-15). 남은 요청은 아래 anyRequest 가 받고,
                        // 매핑이 없으므로 인증된 호출엔 404, 미인증 호출엔 401(CustomAuthenticationEntryPoint)로 끝난다.
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
