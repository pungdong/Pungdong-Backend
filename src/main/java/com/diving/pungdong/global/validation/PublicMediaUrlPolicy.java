package com.diving.pungdong.global.validation;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 본문에 실리는 이미지 URL 이 <b>우리가 발급한 것</b>인지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 2-phase 업로드라 본문은 URL 문자열만 받는데, 아무 URL 이나 통과시키면
 * (a) 게시물에 임의 외부 이미지를 심어 호스트 추적·콘텐츠 변조가 가능해지고
 * (b) 게시물 삭제 로직이 <b>남의 도메인 객체를 지우려 든다.</b>
 *
 * <p>브랜딩 게시물과 커뮤니티 글이 같은 공개 버킷·같은 업로드 엔드포인트를 쓰므로 규칙도 하나여야 한다.
 * 각자 복사해 두면 한쪽만 고쳐지는 순간 갈라진다 — 그래서 {@code global/validation} 으로 뺐다
 * ({@link ImageUploadPolicy} 가 업로드 <i>시점</i>을 막는다면, 이건 <i>참조 시점</i>을 막는다).
 */
@Component
public class PublicMediaUrlPolicy {

    @Value("${pungdong.storage.public-base-url:}")
    private String publicBaseUrl;

    @Value("${pungdong.storage.local.base-url:}")
    private String localBaseUrl;

    /**
     * 우리 CDN(운영) 또는 로컬 업로드 base(개발)로 시작하지 않으면 400.
     *
     * <p>base 가 비어 있는 환경(설정 미주입)에서는 그 축을 검사하지 않는다 — 로컬 stub 개발을 막지
     * 않으려는 것이고, 운영에는 항상 값이 있다.
     */
    public void requireOurs(String url) {
        boolean allowed = (StringUtils.hasText(publicBaseUrl) && url.startsWith(publicBaseUrl + "/"))
                || (StringUtils.hasText(localBaseUrl) && url.startsWith(localBaseUrl + "/"));
        if (!allowed) {
            throw new BadRequestException("업로드로 받은 이미지 주소만 사용할 수 있어요.");
        }
    }
}
