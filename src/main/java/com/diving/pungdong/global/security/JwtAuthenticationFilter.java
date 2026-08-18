package com.diving.pungdong.global.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends GenericFilterBean {

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        String token = jwtTokenProvider.resolveToken((HttpServletRequest) servletRequest);

        if (token != null && jwtTokenProvider.validateToken(token) && !isBlacklisted(token)) {
            Authentication authentication = jwtTokenProvider.getAuthentication(token);
            // 정지된 계정은 인증을 세우지 않는다 → 인증 없는 요청으로 흘러가
            // CustomAuthenticationEntryPoint 가 401 JSON 을 준다.
            if (!isSuspended(authentication)) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    /**
     * 어드민이 정지한 계정인가.
     *
     * <p><b>여기가 정지의 주된 빗장이다.</b> 로그아웃·탈퇴는 "지금 들고 있는 토큰" 을 Redis 블랙리스트에
     * 넣어 막지만, <b>어드민은 남의 토큰 문자열을 알 수 없다</b> — 기기가 여럿이면 더더욱. 대신 이
     * 필터가 요청마다 계정을 DB 에서 다시 읽으므로({@code JwtTokenProvider.getAuthentication} →
     * {@code loadUserByUsername}), 컬럼 하나만 보면 <b>모든 기기의 살아 있는 토큰이 즉시</b> 무효가 된다.
     *
     * <p><b>예외를 던지지 않는 이유</b>: 필터는 DispatcherServlet 보다 앞이라 여기서 던진 예외는
     * {@code @RestControllerAdvice} 에 닿지 못하고 서블릿 에러로 샌다. 인증을 세우지 않고 그대로
     * 흘려보내면 기존 401 경로({@code CustomAuthenticationEntryPoint})가 그대로 처리한다.
     */
    private boolean isSuspended(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof UserAccount
                && ((UserAccount) principal).getAccount().getSuspendedAt() != null;
    }

    /** /sign/logout 가 redis 에 "false" 마커로 저장한 토큰은 만료일까지 인증 거부. */
    private boolean isBlacklisted(String token) {
        String value = redisTemplate.opsForValue().get(token);
        return "false".equals(value);
    }
}
