package com.diving.pungdong.ota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * permitAll 텔레메트리 엔드포인트의 <b>신규 행 생성 상한</b>(IP 단위)용 클라이언트 IP 판정.
 *
 * <p>🚨 <b>왜 {@code request.getRemoteAddr()} 를 그대로 쓰지 않나</b>: BE 는 ALB 뒤 ECS 라 그 값은
 * <b>ALB 의 사설 IP</b> 다. 그대로 버킷 키로 쓰면 전 트래픽이 한 버킷에 들어가고, 상한을 넘는 순간
 * <b>모든 실기기가 429</b> 가 된다.
 *
 * <p><b>왜 {@code server.forward-headers-strategy} 를 켜지 않았나</b>: 그건 전역 설정이라 이 피처 밖
 * (HAL 링크 생성의 scheme/host, 이니시스 콜백 경로의 host 인식 등)까지 동작이 바뀐다. 이 트랙의 테스트가
 * 덮지 못하는 범위라, 헤더를 <b>이 클래스에서만</b> 읽어 영향 범위를 가둔다.
 *
 * <p><b>왜 첫 홉이 아니라 마지막 홉인가</b>: 클라이언트가 {@code X-Forwarded-For} 를 위조해 보내면 ALB 는
 * 그 뒤에 <b>실제 클라이언트 IP 를 덧붙인다</b>. 즉 신뢰할 수 있는 건 마지막 항목이다. (전제: 신뢰 프록시가
 * 정확히 하나 — ALB → ECS task 직결. CloudFront 같은 홉이 앞에 추가되면 이 전제를 다시 봐야 한다.)
 *
 * <p><b>fail-open</b>: 판정이 애매하면 {@link Optional#empty()} 를 돌려주고 <b>호출부는 상한을 건너뛴다.</b>
 * 텔레메트리를 조금 더 받는 것보다 정상 기기를 막는 쪽이 훨씬 나쁘다.
 */
@Slf4j
@Component
public class OtaClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    public Optional<String> resolve(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String lastHop = hops[hops.length - 1].trim();
            return lastHop.isBlank() ? Optional.empty() : Optional.of(lastHop);
        }
        // 프록시가 없는 환경(로컬·테스트)에서는 원격 주소가 곧 클라이언트다.
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? Optional.empty() : Optional.of(remote);
    }
}
