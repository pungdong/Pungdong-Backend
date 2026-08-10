package com.diving.pungdong.global.validation;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 업로드 이미지 공통 정책 — <b>공개 CDN 으로 서빙되는</b> 이미지(프로필·코스·리뷰·브랜딩)의 최소 방어선.
 *
 * <p>왜 필요한가: {@code S3Uploader} 는 클라이언트가 준 {@code Content-Type} 을 그대로 S3 메타데이터에
 * 복사하고 확장자도 파일명을 그대로 신뢰한다. 그 객체는 CloudFront 로 <b>공개 서빙</b>되므로, 타입을
 * 위조한 업로드가 그대로 브라우저에 그 타입으로 내려간다. 업로드 시점이 유일한 차단 지점이다.
 *
 * <p>크기 상한이 있는 별도 이유: 온디맨드 변환({@code cdn.plop.cool/r/*})을 담당하는 Lambda 는 원본을
 * 통째로 메모리에 올린 뒤 결과를 <b>base64 로</b> 반환해서 Function URL 페이로드 상한(~6MB)에 걸린다.
 * 원본이 과도하게 크면 썸네일이 아예 안 만들어진다. (변환 파라미터 없이 {@code /r/{key}} 를 그대로
 * 부르면 원본이 base64 로 실려 나가므로, FE 는 항상 {@code w}/{@code fm} 을 붙여 호출한다.)
 *
 * <p>메시지는 FE 가 그대로 노출하는 사용자 문구라 한국어다.
 */
public final class ImageUploadPolicy {

    /** 허용 MIME — sharp(변환 Lambda)이 다루는 포맷에 맞춘다. HEIC 는 미지원이라 제외. */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    public static final long MAX_BYTES = 8L * 1024 * 1024;

    private ImageUploadPolicy() {
    }

    /** 빈 파일 · 허용되지 않은 타입 · 크기 초과를 400 으로 막는다. S3 를 건드리기 <b>전에</b> 부른다. */
    public static void validate(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BadRequestException("이미지를 선택해주세요.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(normalizedContentType(image))) {
            throw new BadRequestException("지원하지 않는 이미지 형식이에요. JPG · PNG · WEBP 만 올릴 수 있어요.");
        }
        if (image.getSize() > MAX_BYTES) {
            throw new BadRequestException("이미지가 너무 커요. 8MB 이하로 올려주세요.");
        }
    }

    /** {@code "IMAGE/JPEG; charset=utf-8"} 같은 값도 받아들이도록 파라미터를 떼고 소문자로 맞춘다. */
    private static String normalizedContentType(MultipartFile image) {
        String contentType = image.getContentType();
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        int parameterAt = contentType.indexOf(';');
        String bare = parameterAt >= 0 ? contentType.substring(0, parameterAt) : contentType;
        return bare.trim().toLowerCase();
    }
}
