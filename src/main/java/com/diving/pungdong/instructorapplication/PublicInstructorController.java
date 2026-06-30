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
 * 공개 강사 디렉토리 — 수강생 둘러보기 홈 "풍덩 공식 강사" 카드. <b>비로그인 가능</b>(permitAll, 매처는
 * {@code SecurityConfiguration}). 실가입(승인 신청 보유) 강사만, 공개 필드만(PII 없음). {@code page.totalElements}
 * 로 "N명" 파생.
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
