package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.course.dto.CourseImageResult;
import com.diving.pungdong.course.storage.CourseImageStorage;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 코스 이미지 업로드 (2-phase 1단계) — 강의상세 캐로셀 사진. instructor-application 의
 * {@code uploadCertificateImage} 와 동일한 모양: 빈 파일은 400, 저장 실패는 400.
 */
@Service
@RequiredArgsConstructor
public class CourseImageService {

    private final CourseImageStorage courseImageStorage;

    public CourseImageResult uploadCourseImage(Account account, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BadRequestException();
        }
        try {
            String url = courseImageStorage.store(image, account.getEmail());
            return CourseImageResult.builder().fileURL(url).build();
        } catch (IOException e) {
            throw new BadRequestException();
        }
    }
}
