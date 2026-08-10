package com.diving.pungdong.service.review;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.domain.reservation.Reservation;
import com.diving.pungdong.domain.review.Review;
import com.diving.pungdong.domain.review.ReviewImage;
import com.diving.pungdong.dto.review.image.create.ReviewImageInfo;
import com.diving.pungdong.global.validation.ImageUploadPolicy;
import com.diving.pungdong.repo.ReviewImageJpaRepo;
import com.diving.pungdong.service.ReviewService;
import com.diving.pungdong.service.image.S3Uploader;
import com.diving.pungdong.service.reservation.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewImageService {
    private final ReviewImageJpaRepo reviewImageJpaRepo;
    private final S3Uploader s3Uploader;
    private final ReviewService reviewService;
    private final ReservationService reservationService;

    @Transactional
    public List<ReviewImageInfo> saveReviewImages(Account account, Long reservationId, Long reviewId, List<MultipartFile> images) throws IOException {
        Reservation reservation = reservationService.findById(reservationId);
        reviewService.checkPossibleReviewer(reservation.getAccount(), account);

        Review review = reviewService.findByReviewId(reviewId);

        List<ReviewImageInfo> reviewImageInfos = new ArrayList<>();

        // 한 장이라도 규칙에 어긋나면 아무것도 올리지 않는다 — 절반만 저장된 리뷰가 남지 않도록.
        images.forEach(ImageUploadPolicy::validate);

        for (MultipartFile image : images) {
            String fileUrl = s3Uploader.uploadPublic(image, "review-image");
            ReviewImage reviewImage = ReviewImage.builder()
                    .url(fileUrl)
                    .review(review)
                    .build();
            ReviewImage savedReview = reviewImageJpaRepo.save(reviewImage);

            ReviewImageInfo reviewImageInfo = ReviewImageInfo.builder()
                    .reviewImageId(savedReview.getId())
                    .imageUrl(fileUrl)
                    .build();
            reviewImageInfos.add(reviewImageInfo);
        }

        return reviewImageInfos;
    }
}
