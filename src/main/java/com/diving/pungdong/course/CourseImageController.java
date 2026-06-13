package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.course.dto.CourseImageResult;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 코스 이미지 업로드 (2-phase 1단계) — multipart 로 사진을 먼저 올려 URL 을 받고, 코스 생성 JSON 이
 * 그 URL 을 참조한다(자격증 이미지 `POST /instructor-applications/certificate-images` 와 동일 패턴).
 *
 * <p>매처 {@code /course-images} → authenticated ({@code global/security/SecurityConfiguration}).
 * 역할이 아니라 인증인 이유: 강사 신청 리뷰 대기(STUDENT) 중에도 draft 준비를 허용 — venue 와 동일.
 * 강의 본 도메인(Course aggregate)·생성 엔드포인트는 후속 PR.
 */
@RestController
@RequestMapping(value = "/course-images", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class CourseImageController {

    private final CourseImageService courseImageService;

    @PostMapping
    public ResponseEntity<?> uploadCourseImage(@CurrentUser Account account,
                                               @RequestParam("image") MultipartFile image) {
        CourseImageResult uploaded = courseImageService.uploadCourseImage(account, image);

        EntityModel<CourseImageResult> model = EntityModel.of(uploaded);
        model.add(Link.of("/docs/api.html#resource-course-image").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
