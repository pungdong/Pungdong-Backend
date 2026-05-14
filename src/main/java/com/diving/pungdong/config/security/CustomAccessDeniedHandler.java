package com.diving.pungdong.config.security;

import com.diving.pungdong.model.CommonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 권한 부족 요청에 대해 302 redirect 대신 403 JSON 응답을 직접 쓴다.
 * JSON 클라이언트(모바일 / 웹) 가 그대로 파싱해서 처리할 수 있도록.
 */
@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        CommonResult body = new CommonResult();
        body.setSuccess(false);
        body.setCode(Integer.parseInt(messageSource.getMessage("accessDenied.code", null, Locale.KOREAN)));
        body.setMsg(messageSource.getMessage("accessDenied.msg", null, Locale.KOREAN));

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
