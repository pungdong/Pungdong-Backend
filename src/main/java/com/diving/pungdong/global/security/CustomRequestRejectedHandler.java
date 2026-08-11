package com.diving.pungdong.global.security;

import com.diving.pungdong.global.model.CommonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * {@code StrictHttpFirewall} 이 거부한 요청을 <b>400 JSON</b> 으로 응답한다.
 *
 * <p><b>왜 필요한가</b>: 기본 동작은 {@link RequestRejectedException} 이 필터 밖으로 전파돼 <b>500</b> 이
 * 되는 것이다. 방화벽이 거부하는 건 이중 인코딩({@code %25})·인코딩된 슬래시 같은 <b>잘못된 형식의 URL</b>,
 * 즉 클라이언트 입력 문제인데 5xx 로 나가면
 * <ul>
 *   <li>레포 규약(400 = malformed input / 5xx = server fault)과 어긋나고,</li>
 *   <li><b>클라이언트 실수가 서버 장애 알람을 울려</b> 관측을 오염시킨다(실제로 웹의 이중 인코딩 버그가
 *       BE 5xx 로 잡혔다).</li>
 * </ul>
 *
 * <p>방화벽 자체는 그대로 둔다 — path traversal 방어를 푸는 게 아니라 <b>거부를 올바른 상태 코드로
 * 표현</b>하는 것뿐이다. 무엇이 거부됐는지는 응답에 싣지 않고 로그에만 남긴다(공격자에게 방화벽 규칙을
 * 알려줄 이유가 없다).
 *
 * <p>{@code CustomAuthenticationEntryPoint} 와 같은 이유로 {@code Locale.KOREAN} 을 명시한다 —
 * 시큐리티 필터는 {@code LocaleResolver} 보다 먼저 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomRequestRejectedHandler implements RequestRejectedHandler {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       RequestRejectedException ex) throws IOException {
        log.warn("요청이 방화벽에 거부됨: method={} uri={} reason={}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());

        CommonResult body = new CommonResult();
        body.setSuccess(false);
        body.setCode(Integer.parseInt(messageSource.getMessage("badRequest.code", null, Locale.KOREAN)));
        body.setMsg(messageSource.getMessage("badRequest.msg", null, Locale.KOREAN));

        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
