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
                        .antMatchers(HttpMethod.GET, "/courses/browse").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/level-labels").permitAll()
                        .antMatchers(HttpMethod.GET, "/courses/*/detail").permitAll()
                        .antMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .antMatchers(HttpMethod.POST, "/webhooks/sanity/venue").permitAll()
                        .antMatchers("/admin/instructor-applications/**").hasRole("ADMIN")
                        .antMatchers("/instructor-applications/**").authenticated()
                        .antMatchers("/identity-verifications/**").authenticated()
                        .antMatchers("/consents/**").authenticated()
                        .antMatchers("/venues/**").authenticated()
                        .antMatchers("/venue-equipment/**").authenticated()
                        .antMatchers("/instructor/availability/**").authenticated()
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
