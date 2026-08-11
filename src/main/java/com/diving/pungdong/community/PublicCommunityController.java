package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.branding.CommunityCategory;
import com.diving.pungdong.community.dto.CommunityPostCardResponse;
import com.diving.pungdong.community.dto.CommunityPostDetailResponse;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 커뮤니티 읽기 — {@code GET /community/posts/**} (<b>비로그인 가능</b>).
 *
 * <p>permitAll 이라 {@code @CurrentUser} 가 <b>null 일 수 있다.</b> 서비스가 그걸 전제로 "내 반응"
 * 필드를 false 로 채우고, 상세는 오너 예외(숨긴 자기 글 열람)를 viewer 가 있을 때만 적용한다.
 *
 * <p>쓰기와 컨트롤러를 나눈 이유: 시큐리티 매처가 경로 단위라 읽기/쓰기가 같은 경로 아래 섞이면
 * "GET 만 permitAll" 을 표현하기 위해 매처가 HTTP 메서드별로 늘어난다. 클래스를 나눠도 경로는
 * 같으니 클라이언트에는 차이가 없다(브랜딩이 쓰는 방식과 동일).
 */
@RestController
@RequestMapping(value = "/community/posts", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class PublicCommunityController {

    private final CommunityPostService postService;
    private final CommunityCommentService commentService;

    /**
     * 피드. {@code category} 생략이면 전체(카테고리 없는 브랜딩발 글도 포함)다.
     *
     * <p>{@code bookmarkedByMe=true} 는 "저장한 글" 목록이라 인증이 필요하다 — 비로그인이면 400 이
     * 아니라 <b>빈 페이지</b>가 자연스럽다(로그인 안 했으면 저장한 글이 없는 게 맞는 답이다).
     *
     * <p>{@code authorType=INSTRUCTOR} 는 웹 피드의 "강사 글" pill — 승인된 강사가 쓴 글만. 생략은 전체다.
     *
     * <p><b>정렬은 화이트리스트 enum {@code sort=LATEST|POPULAR} 로만 받는다</b>(기본 LATEST).
     * 클라이언트가 준 정렬 문자열을 {@link Pageable} 에 태우지는 않는다 — 그러면 인덱스 없는 정렬이나
     * 내부 컬럼 탐색이 뚫린다. {@code category=MATCH} 는 이 값과 무관하게 <b>일정 임박순으로 자동
     * 전환</b>된다(그 정렬 pill 이 있는 화면이 Phase 1 범위 밖이라 값 대신 기본 동작으로 살렸다).
     */
    @GetMapping
    public ResponseEntity<?> feed(@RequestParam(required = false) CommunityCategory category,
                                  @RequestParam(required = false, defaultValue = "LATEST") FeedSort sort,
                                  @RequestParam(required = false) AuthorType authorType,
                                  @RequestParam(required = false, defaultValue = "false") boolean bookmarkedByMe,
                                  @CurrentUser Account account,
                                  Pageable pageable,
                                  PagedResourcesAssembler<CommunityPostCardResponse> assembler) {
        return ResponseEntity.ok().body(assembler.toModel(
                postService.feed(category, sort, authorType, bookmarkedByMe, account, pageable)));
    }

    /**
     * 댓글 스레드 — 대댓글이 부모 아래 중첩돼 온다.
     *
     * <p>정렬 파라미터가 없다. 서버가 {@code createdAt ASC} 로 고정한다 — 스레드는 위에서 아래로
     * 대화가 흐르는 게 자연스럽고, 디자인의 "최신순 ▾" 은 다른 옵션이 정의된 곳이 없다.
     */
    @GetMapping("/{postId}/comments")
    public ResponseEntity<?> comments(@PathVariable Long postId, @CurrentUser Account account) {
        return ResponseEntity.ok().body(CollectionModel.of(commentService.thread(postId, account)));
    }

    /** 관련 글 — 웹 상세 우측 rail. 같은 카테고리·자기 제외·최신순. */
    @GetMapping("/{postId}/related")
    public ResponseEntity<?> related(@PathVariable Long postId,
                                     @RequestParam(required = false, defaultValue = "3") int limit,
                                     @CurrentUser Account account) {
        return ResponseEntity.ok().body(CollectionModel.of(postService.related(postId, limit, account)));
    }

    /** 상세. 숨김·미노출 글은 400(존재 숨김) — 단 <b>오너 본인은 자기 글이면</b> 볼 수 있다. */
    @GetMapping("/{postId}")
    public ResponseEntity<?> detail(@PathVariable Long postId, @CurrentUser Account account) {
        EntityModel<CommunityPostDetailResponse> model = EntityModel.of(postService.detail(postId, account));
        model.add(Link.of("/docs/api.html#resource-community-post").withRel("profile"));
        return ResponseEntity.ok().body(model);
    }
}
