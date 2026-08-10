package com.diving.pungdong.global.validation;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공개 이미지 업로드 정책의 사양.
 *
 * <p><b>읽는 법</b>: {@code @DisplayName} 을 위에서 아래로 = 사양. S* 통과 / V* 거부.
 *
 * <p>왜 이 정책이 있나: 업로드된 객체는 CloudFront 로 <b>공개 서빙</b>되고, {@code S3Uploader} 는
 * 클라이언트가 준 {@code Content-Type} 을 그대로 S3 메타데이터에 복사한다. 업로드 시점이 유일한
 * 차단 지점이라 여기서 막지 않으면 위조된 타입이 그대로 브라우저에 내려간다.
 */
class ImageUploadPolicyTest {

    private MockMultipartFile file(String contentType, byte[] bytes) {
        return new MockMultipartFile("image", "photo.png", contentType, bytes);
    }

    /* ════════════════ S — 통과 ════════════════ */

    @Test
    @DisplayName("S1: JPG · PNG · WEBP 는 통과한다")
    void allowedTypes_pass() {
        byte[] bytes = "fake-bytes".getBytes();

        assertThatCode(() -> ImageUploadPolicy.validate(file("image/jpeg", bytes))).doesNotThrowAnyException();
        assertThatCode(() -> ImageUploadPolicy.validate(file("image/png", bytes))).doesNotThrowAnyException();
        assertThatCode(() -> ImageUploadPolicy.validate(file("image/webp", bytes))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("S2: 대소문자·파라미터가 붙은 Content-Type 도 통과한다 (일부 클라이언트가 그렇게 보낸다)")
    void contentTypeIsNormalized() {
        assertThatCode(() -> ImageUploadPolicy.validate(file("IMAGE/JPEG; charset=utf-8", "x".getBytes())))
                .doesNotThrowAnyException();
    }

    /* ════════════════ V — 거부 ════════════════ */

    @Test
    @DisplayName("V1: 빈 파일이면 400")
    void emptyFile_rejected() {
        assertThatThrownBy(() -> ImageUploadPolicy.validate(file("image/png", new byte[0])))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미지를 선택해주세요.");
    }

    @Test
    @DisplayName("V2: 이미지가 아닌 타입(pdf)이면 400 — 확장자가 .png 로 위장돼 있어도 막는다")
    void nonImageType_rejected() {
        assertThatThrownBy(() -> ImageUploadPolicy.validate(file("application/pdf", "%PDF".getBytes())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("지원하지 않는 이미지 형식");
    }

    @Test
    @DisplayName("V3: Content-Type 이 없으면 400 — 검증할 수 없는 값은 통과시키지 않는다")
    void missingContentType_rejected() {
        assertThatThrownBy(() -> ImageUploadPolicy.validate(file(null, "x".getBytes())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("지원하지 않는 이미지 형식");
    }

    @Test
    @DisplayName("V4: 상한(8MB)을 넘으면 400 — 변환 Lambda 가 원본을 메모리에 올리고 base64 로 반환하기 때문")
    void tooLarge_rejected() {
        byte[] tooBig = new byte[(int) ImageUploadPolicy.MAX_BYTES + 1];

        assertThatThrownBy(() -> ImageUploadPolicy.validate(file("image/jpeg", tooBig)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("이미지가 너무 커요");
    }
}
