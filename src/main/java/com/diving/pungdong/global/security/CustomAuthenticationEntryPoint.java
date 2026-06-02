package com.diving.pungdong.global.security;

import com.diving.pungdong.global.model.CommonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 인증되지 않은 요청에 대해 302 redirect 대신 401 JSON 응답을 직접 쓴다.
 * JSON 클라이언트(모바일 / 웹) 가 그대로 파싱해서 처리할 수 있도록.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        CommonResult body = new CommonResult();
        body.setSuccess(false);
        // SecurityFilterChain 은 LocaleResolver 이전에 실행되므로 Korean 명시
        body.setCode(Integer.parseInt(messageSource.getMessage("entryPointException.code", null, Locale.KOREAN)));
        body.setMsg(messageSource.getMessage("entryPointException.msg", null, Locale.KOREAN));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
