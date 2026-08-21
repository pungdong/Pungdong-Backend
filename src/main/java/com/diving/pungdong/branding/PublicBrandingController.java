package com.diving.pungdong.branding;

import com.diving.pungdong.branding.dto.BrandingPostCardResponse;
import com.diving.pungdong.branding.dto.BrandingProfileResponse;
import com.diving.pungdong.branding.dto.InstructorBrowseCardResponse;
import com.diving.pungdong.branding.dto.InstructorBrowseCondition;
import com.diving.pungdong.branding.dto.SuggestedInstructorsResponse;
import com.diving.pungdong.account.Account;
import com.diving.pungdong.global.hateoas.WhitelistSortLinks;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 공개 브랜딩 페이지 / 내 프로필 — {@code GET /instructors/{nickName}} (<b>비로그인 가능</b>).
 *
 * <p><b>왜 id 가 아니라 닉네임인가</b>: 순차 id 를 공개 URL 에 노출하면 열거로 전수 스크래핑이 된다
 * (루트 CLAUDE.md 의 anti-IDOR 규칙). 전용 handle 을 신설하는 안도 있었으나 사용자 결정(D3)으로
 * <b>닉네임을 URL 식별자로</b> 쓴다 — 대신 닉네임에 형식 가드·예약어 차단이 붙는다.
 *
 * <p>경로가 기존 {@code GET /instructors/public}(공개 강사 목록)과 한 네임스페이스를 쓴다. Spring MVC 는
 * <b>리터럴을 path variable 보다 우선</b>하므로 라우팅은 안전하다. 다만 닉네임이 정확히 {@code "public"}
 * 인 계정은 프로필이 열리지 않으므로 예약어로 막는다.
 *
 * <p>없는 닉네임·미발행·탈퇴는 전부 <b>400(존재 숨김)</b> — 이 레포는 404 를 쓰지 않는다.
 */
@RestController
@RequestMapping(value = "/instructors", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class PublicBrandingController {

    private final BrandingService brandingService;
    private final BrandingPostService postService;
    private final SuggestedInstructorService suggestedInstructorService;
    private final InstructorBrowseService instructorBrowseService;

    /**
     * {@code nickName} 은 percent-encoding 으로 전달된다(한글·공백 등). Spring 이 <b>디코딩된 값</b>을
     * 넘겨주므로 여기서 추가 디코딩을 하면 안 된다(이중 디코딩 버그).
     */
    /**
     * 추천 강사 — 커뮤니티 사이드바("이 강사님은 어때요?")와 홈의 공식 강사 카드. <b>비로그인 가능</b>.
     *
     * <p>{@code /{nickName}} <b>앞</b>에 둔다. Spring 은 리터럴을 path variable 보다 우선하므로 라우팅
     * 자체는 순서와 무관하지만, 읽는 사람에게 "이 네임스페이스의 리터럴 경로" 를 먼저 보이게 하려는 것이다.
     * ⚠️ 닉네임 {@code "suggested"} 는 예약어로 막힌다({@code global/validation/NickNamePolicy}) —
     * 안 막으면 그 닉네임을 가진 사람의 프로필이 영영 안 열린다({@code "public"} 과 같은 이유).
     */
    @GetMapping("/suggested")
    public ResponseEntity<?> suggested(@CurrentUser Account viewer,
                                       @RequestParam(required = false, defaultValue = "5") int limit) {
        EntityModel<SuggestedInstructorsResponse> model =
                EntityModel.of(suggestedInstructorService.suggest(limit, viewer));
        model.add(Link.of("/docs/api.html#resource-instructors-suggested").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /**
     * 강사 둘러보기 — 홈 "풍덩 공식 강사" 더보기에서 들어오는 무한 스크롤 목록. <b>비로그인 가능</b>.
     *
     * <p>위 {@code /suggested} 와 <b>모수는 같고(승인 ∧ 발행) 성격이 다르다</b>: 저쪽은 매번 다시 뽑는
     * 무작위 위젯이라 페이지네이션이 불가능하고, 이쪽은 필터·검색·정렬이 붙는 목록이다.
     * {@code /instructors/public} 과는 <b>모수가 다르다</b> — 그쪽은 발행을 안 봐서 눌러도 400 인 카드가 섞인다.
     *
     * <p>{@code sort} 는 화이트리스트 enum 이다. Spring {@code Pageable} 형식({@code ?sort=field,dir})을
     * 보내면 무시가 아니라 <b>변환 실패로 400</b> 이니 계약서에 그렇게 적혀 있어야 한다.
     * ⚠️ 닉네임 {@code "browse"} 는 예약어로 막았다({@code NickNamePolicy}) — 안 막으면 그 닉네임을 가진
     * 사람의 프로필이 이 리터럴에 가려 영영 안 열린다({@code "public"}·{@code "suggested"} 와 같은 이유).
     */
    @GetMapping("/browse")
    public ResponseEntity<?> browse(@RequestParam(required = false) String disciplineCode,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) List<String> organizationCodes,
                                    @RequestParam(required = false) Boolean hasOpenCourse,
                                    @RequestParam(required = false) InstructorBrowseCondition.Sort sort,
                                    Pageable pageable,
                                    PagedResourcesAssembler<InstructorBrowseCardResponse> assembler) {
        InstructorBrowseCondition condition = InstructorBrowseCondition.builder()
                .disciplineCode(disciplineCode)
                .keyword(keyword)
                .organizationCodes(organizationCodes)
                .hasOpenCourse(hasOpenCourse)
                .sort(sort)
                .build();
        PagedModel<EntityModel<InstructorBrowseCardResponse>> model =
                assembler.toModel(instructorBrowseService.browse(condition, pageable));
        // assembler 는 요청의 sort 를 지우고 Page 의 Sort 를 붙이는데, 서비스가 Sort 를 버렸으므로
        // 그냥 사라진다 → next 를 따라가면 기본 정렬로 계산돼 강사가 중복·누락된다. 되붙인다.
        WhitelistSortLinks.apply(model, sort);
        model.add(Link.of("/docs/api.html#resource-instructors-browse").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    @GetMapping("/{nickName}")
    public ResponseEntity<?> publicProfile(@CurrentUser Account viewer, @PathVariable String nickName) {
        EntityModel<BrandingProfileResponse> model =
                EntityModel.of(brandingService.publicProfile(nickName, viewer));
        model.add(Link.of("/docs/api.html#resource-branding-public").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }

    /**
     * 공개 그리드 — 숨긴 글 제외, 고정 먼저 최신순. 정렬은 서버가 고정하고 {@code size} 는 상한을 둔다
     * (클라이언트가 정렬을 바꾸거나 size 를 키워 전수 스크래핑하지 못하게).
     */
    @GetMapping("/{nickName}/posts")
    public ResponseEntity<?> publicPosts(@CurrentUser Account viewer,
                                         @PathVariable String nickName,
                                         Pageable pageable,
                                         PagedResourcesAssembler<BrandingPostCardResponse> assembler) {
        PagedModel<EntityModel<BrandingPostCardResponse>> model =
                assembler.toModel(postService.publicGrid(nickName, pageable, viewer));
        model.add(Link.of("/docs/api.html#resource-branding-posts").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
