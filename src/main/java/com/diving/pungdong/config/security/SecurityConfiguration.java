package com.diving.pungdong.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private final JwtTokenProvider jwtTokenProvider;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .httpBasic(httpBasic -> httpBasic.disable())
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
                        .antMatchers("/sign/instructor/request/list", "/sign/instructor/confirm").hasRole("ADMIN")
                        .antMatchers("/account/instructor/**").hasRole("INSTRUCTOR")
                        .antMatchers("/lecture/create", "/lecture/update", "/lecture/delete", "/lecture/manage/list",
                                "/location/create", "/lectureImage/create/list", "/equipment/create/list").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .accessDeniedHandler(new CustomAccessDeniedHandler())
                        .authenticationEntryPoint(new CustomAuthenticationEntryPoint())
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring()
                .requestMatchers(PathRequest.toStaticResources().atCommonLocations())
                .antMatchers("/docs/**", "/webjars/**");
    }
}
