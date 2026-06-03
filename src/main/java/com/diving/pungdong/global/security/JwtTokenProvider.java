package com.diving.pungdong.global.security;

import com.diving.pungdong.account.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class JwtTokenProvider {

    @Value("${spring.jwt.secret}")
    private String secretKey;

    /** Access token 유효기간: 1시간. */
    private static final long ACCESS_TOKEN_VALID_MS = 1000L * 60 * 60;

    /**
     * Refresh token 유효기간: 30일.
     * rotation(매 refresh 마다 재발급)과 결합해 슬라이딩 윈도우로 동작 —
     * 30일 = "이 기간 안에 한 번도 접근 안 하면 재로그인" 즉 최대 비활성 허용 기간.
     */
    private static final long REFRESH_TOKEN_VALID_MS = 1000L * 60 * 60 * 24 * 30;

    private final UserDetailsService userDetailsService;

    @PostConstruct
    protected void init() {
        secretKey = Base64.getEncoder().encodeToString(secretKey.getBytes());
    }

    public int getAccessTokenValiditySeconds() {
        return (int) (ACCESS_TOKEN_VALID_MS / 1000);
    }

    /** 로그아웃 블랙리스트 TTL / refresh rotation 시 옛 토큰 무효화 TTL 로 사용. */
    public long getRefreshTokenValidMs() {
        return REFRESH_TOKEN_VALID_MS;
    }

    /** 로그아웃 블랙리스트 TTL 로 사용. */
    public long getAccessTokenValidMs() {
        return ACCESS_TOKEN_VALID_MS;
    }

    public String createAccessToken(String userPk, Set<Role> roles) {
//        Claims claims = Jwts.claims().setSubject(userPk);
        Claims claims = Jwts.claims();
        claims.put("user_name", userPk);
        claims.put("roles", roles);
        Date date = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(date)
                .setExpiration(new Date(date.getTime() + ACCESS_TOKEN_VALID_MS))
                .signWith(SignatureAlgorithm.HS256, secretKey)
                .compact();
    }

    public String createRefreshToken(String userPk) {
//        Claims claims = Jwts.claims().setSubject(userPk);
        Claims claims = Jwts.claims();
        claims.put("user_name", userPk);
        Date date = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(date)
                .setExpiration(new Date(date.getTime() + REFRESH_TOKEN_VALID_MS))
                .signWith(SignatureAlgorithm.HS256, secretKey)
                .compact();
    }

    public Authentication getAuthentication(String token) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(this.getUserPk(token));
        return new UsernamePasswordAuthenticationToken(userDetails, "", userDetails.getAuthorities());
    }

    public String getUserPk(String token) {
//        return Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token).getBody().getSubject();
        return Jwts.parser().setSigningKey(secretKey).parseClaimsJws(token).getBody().get("user_name", String.class);
    }

    public String resolveToken(HttpServletRequest request) {
        return request.getHeader("Authorization");
    }

    public Boolean isRefreshToken(HttpServletRequest request) {
        String result = request.getHeader("IsRefreshToken");
        if (result == null) {
            return null;
        }
        return Boolean.valueOf(result);
    }

    public boolean validateToken(String jwtToken) {
        try {
            Jws<Claims> claims = Jwts.parser().setSigningKey(secretKey).parseClaimsJws(jwtToken);
            return !claims.getBody().getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

}
