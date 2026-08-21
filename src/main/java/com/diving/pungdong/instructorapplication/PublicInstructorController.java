package com.diving.pungdong.instructorapplication;

import com.diving.pungdong.instructorapplication.dto.PublicInstructorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 강사 디렉토리 — 승인 신청을 가진 실가입 강사 <b>전체</b> 목록. <b>비로그인 가능</b>(permitAll, 매처는
 * {@code SecurityConfiguration}). 공개 필드만(PII 없음).
 *
 * <p>🔴 <b>현재 호출자가 없다(2026-08-22 확인).</b> 이 javadoc 은 오래 "홈의 «풍덩 공식 강사» 카드" 라고
 * 적고 있었지만 <b>사실이 아니다</b> — 홈 카드는 2026-08-18 에
 * {@code GET /instructors/suggested}(승인 + <b>프로필 발행</b>, 무작위 N명 + {@code totalCount})로 갈아탔고
 * 그때 이쪽 설명이 갱신되지 않았다. 두 엔드포인트가 서로 홈 카드의 주인이라고 주장하는 상태였다.
 *
 * <p><b>남겨 둔 이유</b>: 배포된 구버전 앱 빌드가 아직 부를 수 있다. 제거 조건은
 * {@code CourseDetailResponse} 의 {@code instructorId}/{@code instructorName} 과 <b>같다</b> —
 * 클라이언트 OTA 반영 확인 후 함께 정리한다.
 *
 * <p>⚠️ 경로 리터럴 {@code /instructors/public} 자체는 <b>지워도 예약어를 풀면 안 된다</b> —
 * 닉네임 {@code public} 은 {@code /instructors/{nickName}} 과 충돌할 소지가 계속 남는다
 * ({@code global/validation/NickNamePolicy}).
 */
@RestController
@RequestMapping(value = "/instructors", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class PublicInstructorController {

    private final PublicInstructorService publicInstructorService;

    @GetMapping("/public")
    public ResponseEntity<?> listPublic(Pageable pageable,
                                        PagedResourcesAssembler<PublicInstructorResponse> assembler) {
        Page<PublicInstructorResponse> page = publicInstructorService.listPublicInstructors(pageable);
        PagedModel<EntityModel<PublicInstructorResponse>> model = assembler.toModel(page);
        model.add(Link.of("/docs/api.html#resource-instructors-public").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
