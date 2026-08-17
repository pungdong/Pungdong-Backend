package com.diving.pungdong.community;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.branding.*;
import com.diving.pungdong.branding.dto.LinkedCourseResponse;
import com.diving.pungdong.community.dto.*;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.sitesettings.SiteSettingsProvider;
import com.diving.pungdong.global.validation.PublicMediaUrlPolicy;
import com.diving.pungdong.instructorapplication.InstructorApplication;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import com.diving.pungdong.global.storage.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 커뮤니티 피드·상세·작성. 게시물 엔티티는 브랜딩과 공유하는 {@link BrandingPost} 다(패키지 CLAUDE.md).
 *
 * <p><b>이 서비스의 성능 설계는 "페이지 단위 일괄 조회" 하나로 요약된다.</b> 카드 한 장에 미디어·좋아요
 * 수·댓글 수·북마크 수·내 반응·작성자의 강사 여부·강의 수가 붙는데, 이걸 카드마다 조회하면 페이지 크기
 * × 7 번의 쿼리가 나간다. 대신 <b>페이지를 먼저 가져온 뒤 id 를 모아 각각 한 번씩</b> 조회하고 메모리에서
 * 붙인다 — 브랜딩 그리드가 미디어를 그렇게 처리한 것과 같은 패턴이고, 쿼리 수가 페이지 크기와 무관해진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityPostService {

    /** 클라이언트가 size 를 키워 전수 스크래핑하는 걸 막는다(브랜딩 그리드와 같은 상한). */
    private static final int MAX_PAGE_SIZE = CommunityPaging.MAX_PAGE_SIZE;

    /** 카드 그리드가 3장 + "+N" 오버레이 구조라 앞 3장만 싣는다. */
    private static final int CARD_THUMBNAIL_COUNT = 3;

    /** 카드 본문 미리보기 길이. FE 가 CSS 로 3줄 클램프를 거니 넉넉해야 줄이 꽉 찬다. */
    private static final int EXCERPT_LENGTH = 200;

    /**
     * 인기순·카테고리 카운트의 집계 창(일). 둘 다 "최근 N일" 로 자르는 이유가 같다 —
     * 자르지 않으면 오래된 글이 상단·숫자를 영구히 차지해 피드가 굳고 "이번 주" 라는 라벨이 거짓이 된다.
     */
    private static final int POPULAR_WINDOW_DAYS = 7;

    private final CommunityPostJpaRepo postRepo;
    private final CommunityPostMatchJpaRepo matchRepo;
    private final CommunityPostLikeJpaRepo likeRepo;
    private final CommunityPostBookmarkJpaRepo bookmarkRepo;
    private final CommunityCommentJpaRepo commentRepo;
    /** 숨김 해제 시 "어드민이 조치한 글인가" 를 본다 — 조치를 작성자가 무효화하지 못하게. */
    private final ContentReportJpaRepo reportRepo;
    private final BrandingPostMediaJpaRepo mediaRepo;
    private final AccountBrandingJpaRepo brandingRepo;
    private final AccountJpaRepo accountRepo;
    private final CourseJpaRepo courseRepo;
    /** 강사 여부·강의 수 합성. 댓글 서비스와 같은 컴포넌트를 써야 같은 사람이 두 화면에서 같게 보인다. */
    private final CommunityAuthorComposer authorComposer;
    private final PublicMediaUrlPolicy mediaUrlPolicy;
    private final S3Uploader s3Uploader;

    /* ─── 조회 ───────────────────────────────────────────── */

    /**
     * 피드. {@code viewer} 는 비로그인이면 null 이고, 그때 내 반응 필드는 전부 false 다.
     *
     * <p>{@code MATCH} 카테고리는 <b>일정 임박순</b>으로 자동 정렬된다 — 정렬 축이 조인 테이블에 있어
     * 전용 쿼리를 탄다. 나머지는 최신순.
     */
    public Page<CommunityPostCardResponse> feed(CommunityCategory category,
                                                FeedSort sort,
                                                AuthorType authorType,
                                                boolean bookmarkedByMe,
                                                Account viewer,
                                                Pageable pageable) {
        Pageable page = CommunityPaging.fixed(pageable);

        // "저장한 글" 은 로그인해야 의미가 있다. 비로그인은 에러가 아니라 빈 페이지가 맞는 답이다 —
        // 로그인 안 한 사람에게 저장한 글이 없는 건 정상 상태지 실패가 아니다(레포 규칙).
        if (bookmarkedByMe && viewer == null) {
            return Page.empty(page);
        }

        // 전용 쿼리(인기순·같이가요)는 Specification 을 못 태워서 같은 조건을 파라미터로 넘긴다.
        boolean instructorOnly = authorType == AuthorType.INSTRUCTOR;

        Page<BrandingPost> posts;
        if (sort == FeedSort.POPULAR && !bookmarkedByMe) {
            OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(POPULAR_WINDOW_DAYS);
            posts = category == null
                    ? postRepo.findPopularFeed(since, instructorOnly, page)
                    : postRepo.findPopularFeedByCategory(since, category, instructorOnly, page);
        } else if (category == CommunityCategory.MATCH && !bookmarkedByMe) {
            posts = postRepo.findMatchFeed(instructorOnly, page);
        } else {
            Specification<BrandingPost> spec = Specification.where(CommunityPostSpecifications.feedVisible())
                    .and(CommunityPostSpecifications.category(category))
                    .and(CommunityPostSpecifications.authoredBy(authorType))
                    .and(bookmarkedByMe ? CommunityPostSpecifications.bookmarkedBy(viewer.getId()) : null);
            posts = postRepo.findAll(spec, withLatestSort(page));
        }

        return posts.map(cardMapperFor(posts.getContent(), viewer));
    }

    /**
     * 상세. 숨김·미노출 글은 <b>없는 것으로 취급</b>하되, <b>오너 본인에게는 열어준다</b> —
     * 상세 화면에서 바로 "다시 공개"를 누를 수 있어야 하기 때문(브랜딩과 같은 판단).
     */
    public CommunityPostDetailResponse detail(Long postId, Account viewer) {
        BrandingPost post = postRepo.findVisibleInFeed(postId)
                .orElseGet(() -> viewer == null
                        ? null
                        : postRepo.findMine(postId, viewer.getId()).orElse(null));
        if (post == null) {
            throw new ResourceNotFoundException();
        }
        return toDetail(post, viewer);
    }

    /**
     * 카테고리별 이번 주 글 수 — 4-up 그리드.
     *
     * <p><b>4종을 항상 전부 돌려준다.</b> 글이 0개인 카테고리도 칸은 그려져야 하는데, 집계 결과에는
     * 행이 아예 없어서 그대로 주면 FE 가 칸을 못 그린다. 0 으로 채워서 보낸다 — 이건 없는 값을
     * 지어내는 게 아니라 <b>실제로 0</b> 이다.
     */
    public List<CategoryCountResponse> categoryCounts() {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(POPULAR_WINDOW_DAYS);
        Map<CommunityCategory, Long> counted = new EnumMap<>(CommunityCategory.class);
        for (Object[] row : postRepo.countByCategorySince(since)) {
            counted.put((CommunityCategory) row[0], (Long) row[1]);
        }
        return Arrays.stream(CommunityCategory.values())
                .map(category -> CategoryCountResponse.builder()
                        .category(category)
                        .weeklyPostCount(counted.getOrDefault(category, 0L))
                        .build())
                .collect(Collectors.toList());
    }

    /** 인기 태그 — 웹 sidebar. 건수 내림차순, 동률이면 사전순(순서가 매 요청 흔들리지 않게). */
    public List<PopularTagResponse> popularTags(int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        return postRepo.countPopularTags(PageRequest.of(0, size)).stream()
                .map(row -> PopularTagResponse.builder()
                        .tag((String) row[0]).count((Long) row[1]).build())
                .collect(Collectors.toList());
    }

    /**
     * 관련 글 — 웹 상세 우측 rail. 같은 카테고리·자기 제외·최신순.
     *
     * <p>카테고리가 없는 글(브랜딩발)에는 관련 글이 없다 — 묶을 축이 없어서다. 임의로 전체 최신글을
     * 채우지 않는다("관련" 이라고 부르면서 무관한 걸 보여주는 게 더 나쁘다).
     */
    public List<CommunityPostCardResponse> related(Long postId, int limit, Account viewer) {
        BrandingPost post = postRepo.findVisibleInFeed(postId).orElseThrow(ResourceNotFoundException::new);
        if (post.getCategory() == null) {
            return List.of();
        }
        int size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<BrandingPost> posts = postRepo.findRelated(post.getCategory(), postId, PageRequest.of(0, size));
        return posts.stream().map(cardMapperFor(posts, viewer)).collect(Collectors.toList());
    }

    /* ─── 작성 ───────────────────────────────────────────── */

    /**
     * 작성 — 브랜딩 프로필이 없으면 여기서 만든다(upsert). 게시물이 프로필보다 먼저 생길 수 있고,
     * 그 경우에도 작성자 정보를 붙일 자리가 필요하다.
     */
    @Transactional
    public CommunityPostDetailResponse create(Account currentUser, CommunityPostRequest request) {
        Account owner = loadAccount(currentUser);
        AccountBranding branding = brandingRepo.findByAccountId(owner.getId())
                .orElseGet(() -> brandingRepo.save(AccountBranding.builder()
                        .account(owner).isPublished(true).build()));

        // 노출은 브랜딩 → 커뮤니티 단방향이다. 커뮤니티에서 쓴 글은 피드에만 있고 프로필 그리드엔
        // 올라가지 않는다 — 브랜딩은 "남기고 싶은 하이라이트" 라서 흐름의 모든 글이 거기 갈 이유가 없다.
        BrandingPost post = BrandingPost.builder()
                .branding(branding)
                .showInFeed(true)
                .showOnProfile(false)
                .build();

        apply(post, request, owner);
        postRepo.save(post);
        applyMatch(post, request);
        return toDetail(post, owner);
    }

    /** 수정 — 미디어·태그를 스냅샷으로 교체한다. */
    @Transactional
    public CommunityPostDetailResponse update(Account currentUser, Long postId, CommunityPostRequest request) {
        Account owner = loadAccount(currentUser);
        BrandingPost post = requireMine(postId, owner.getId());

        List<String> removed = urlsOf(post);
        apply(post, request, owner);
        applyMatch(post, request);

        // 교체로 빠진 사진은 아무도 참조하지 않는다 — 안 지우면 S3 고아로 쌓인다.
        removed.removeAll(request.getMediaUrls());
        deleteObjectsQuietly(removed);

        return toDetail(post, owner);
    }

    /**
     * 삭제 — 게시물은 hard delete 다(댓글만 soft delete).
     *
     * <p><b>딸린 자식 행(좋아요·북마크·모집정보·댓글·댓글좋아요)은 여기서 지우지 않는다</b> —
     * FK 가 {@code ON DELETE CASCADE} 라 DB 가 정리한다. 서비스에서 순서대로 지우면 <b>브랜딩 삭제
     * 경로가 같은 정리를 못 해</b> 같은 글이 어느 문으로 들어오느냐에 따라 500 이 난다
     * (브랜딩은 커뮤니티를 import 할 수 없다 — 단방향 의존). 정리 책임은 한 곳(DB)에 둔다.
     */
    @Transactional
    public void delete(Account currentUser, Long postId) {
        BrandingPost post = requireMine(postId, currentUser.getId());
        List<String> urls = urlsOf(post);

        postRepo.delete(post);

        deleteObjectsQuietly(urls);
    }

    /**
     * 숨김 설정 — 삭제가 아니라 <b>되돌릴 수 있는</b> 상태다. 요청의 {@code hidden} 을 그대로 반영하는
     * <b>명시적 값</b>이지 현재 상태를 뒤집는 토글이 아니다(같은 값을 두 번 보내면 no-op).
     *
     * <p><b>{@code is_hidden} 은 브랜딩 그리드도 보는 공유 컬럼이다</b> — 커뮤니티에서 숨기면
     * 강사 프로필 공개 그리드에서도 빠진다(오너 그리드엔 남는다). 클라이언트는 그 파급을 고지해야 한다.
     *
     * <p><b>단, 어드민이 조치한 글은 작성자가 다시 공개할 수 없다.</b> 숨김의 주인이 둘(작성자·어드민)인데
     * 상태 컬럼이 하나뿐이라, 막지 않으면 신고로 내린 글을 작성자가 토글 한 번으로 되살려 조치가
     * 무효가 된다. 되돌리는 건 어드민이 신고를 기각(DISMISSED)하는 경로다.
     */
    @Transactional
    public CommunityPostDetailResponse updateHidden(Account currentUser, Long postId, boolean hidden) {
        BrandingPost post = requireMine(postId, currentUser.getId());
        if (!hidden && reportRepo.existsByTargetTypeAndTargetIdAndStatus(
                ReportTargetType.POST, postId, ReportStatus.ACTIONED)) {
            throw new BadRequestException("신고로 비공개 처리된 글이라 다시 공개할 수 없어요.");
        }
        post.setHidden(hidden);
        return toDetail(post, currentUser);
    }

    /* ─── 내부: 쓰기 ─────────────────────────────────────── */

    private void apply(BrandingPost post, CommunityPostRequest request, Account owner) {
        request.getMediaUrls().forEach(mediaUrlPolicy::requireOurs);

        post.setCategory(request.getCategory());
        post.setTitle(request.getTitle());
        post.setCaption(request.getBody());
        post.setLocationLabel(request.getLocationLabel());
        post.setLinkedCourse(resolveLinkedCourse(request, owner));

        List<BrandingPostMedia> media = new ArrayList<>();
        for (int i = 0; i < request.getMediaUrls().size(); i++) {
            media.add(BrandingPostMedia.builder()
                    .kind(BrandingMediaKind.PHOTO)
                    .url(request.getMediaUrls().get(i))
                    .sortOrder(i)
                    .build());
        }
        post.replaceMedia(media);

        post.replaceTags(request.getTags().stream()
                .map(tag -> BrandingPostTag.builder().tag(tag).build())
                .collect(Collectors.toList()));
    }

    /**
     * 같이가요 모집정보 — {@code MATCH} 면 필수, 아니면 있던 것도 지운다(카테고리를 바꾼 경우).
     *
     * <p><b>"미래 일정" 검증이 DTO 가 아니라 여기 있는 이유</b>는 {@code MatchRequest.meetDate} 의
     * Javadoc 에 있다 — 요청만으로는 판정할 수 없고 <b>저장된 일정과 비교</b>해야 한다.
     */
    private void applyMatch(BrandingPost post, CommunityPostRequest request) {
        if (request.getCategory() != CommunityCategory.MATCH) {
            matchRepo.deleteByPostId(post.getId());
            return;
        }
        CommunityPostRequest.MatchRequest input = request.getMatch();
        if (input == null) {
            throw new BadRequestException("일정·모집 인원·요구 자격을 입력해주세요.");
        }
        CommunityPostMatch match = matchRepo.findById(post.getId())
                .orElseGet(() -> CommunityPostMatch.builder().post(post).build());

        requireFutureIfRescheduled(match.getMeetDate(), input.getMeetDate());

        match.setMeetDate(input.getMeetDate());
        match.setMeetTime(input.getMeetTime());
        match.setCapacity(input.getCapacity());
        match.setLevelLabel(input.getLevelLabel());
        matchRepo.save(match);
    }

    /**
     * <b>새로 잡는 일정일 때만</b> 미래를 요구한다.
     *
     * <p>{@code previous} 가 null 이면 새 모집(작성이거나 다른 카테고리 → MATCH 전환)이고,
     * 값이 다르면 일정 변경이다. 둘 다 "지금부터 모집하는 자리" 라 과거일 수 없다.
     * <b>일정을 그대로 둔 수정은 통과한다</b> — 지난 모집글의 제목·본문·사진을 고치는 건 막을 이유가 없고,
     * 막으면 프리필한 과거 날짜 때문에 그 글이 영구히 편집 불가가 된다.
     */
    private void requireFutureIfRescheduled(LocalDate previous, LocalDate next) {
        // 필드의 @NotNull 이 여기까지 오는 걸 막고 있지만, 그 보증이 이제 먼 곳(DTO)에 있다.
        // 없으면 previous 유무에 따라 NPE 나 "조용히 통과 → nullable=false 컬럼에서 500" 으로 갈린다.
        // 둘 다 400 이 맞는 자리라 여기서 한 번 더 막는다.
        if (next == null) {
            throw new BadRequestException("일정을 골라주세요.");
        }
        if (Objects.equals(previous, next)) {
            return;
        }
        if (next.isBefore(LocalDate.now())) {
            throw new BadRequestException("지난 날짜로는 모집할 수 없어요.");
        }
    }

    /**
     * 연결 강의 해석. <b>강사만·내 코스만</b> 연결할 수 있고, <b>같이가요 글에는 연결할 수 없다</b>
     * (영리활동 금지 가드 중 기계적으로 강제 가능한 부분).
     */
    private Course resolveLinkedCourse(CommunityPostRequest request, Account owner) {
        Long courseId = request.getLinkedCourseId();
        if (courseId == null) {
            return null;
        }
        if (request.getCategory() == CommunityCategory.MATCH) {
            throw new BadRequestException("같이가요 글에는 강의를 연결할 수 없어요.");
        }
        Course course = courseRepo.findById(courseId).orElseThrow(ResourceNotFoundException::new);
        if (!Objects.equals(course.getInstructor().getId(), owner.getId())) {
            // 남의 코스는 403 이 아니라 400 — 존재 여부 자체를 알려주지 않는다(anti-IDOR).
            throw new ResourceNotFoundException();
        }
        return course;
    }

    /* ─── 내부: 조회 조립 ─────────────────────────────────── */

    /**
     * 카드 매퍼 — <b>페이지 전체에 대한 일괄 조회를 먼저 끝내고</b> 그 결과를 클로저로 들고 카드를 만든다.
     * 이렇게 하지 않으면 카드마다 미디어·카운트·작성자 쿼리가 나간다.
     */
    private Function<BrandingPost, CommunityPostCardResponse> cardMapperFor(List<BrandingPost> posts, Account viewer) {
        List<Long> postIds = posts.stream().map(BrandingPost::getId).collect(Collectors.toList());

        Map<Long, List<BrandingPostMedia>> mediaByPost = mediaByPost(postIds);
        Map<Long, Long> likes = countMap(likeRepo.countByPostIds(postIds));
        Map<Long, Long> bookmarks = countMap(bookmarkRepo.countByPostIds(postIds));
        Map<Long, Long> comments = countMap(commentRepo.countByPostIds(postIds));
        Map<Long, CommunityPostMatch> matches = matchRepo.findAllByPostIds(postIds).stream()
                .collect(Collectors.toMap(CommunityPostMatch::getPostId, Function.identity()));

        Set<Long> likedByMe = viewerFlags(viewer, postIds, likeRepo::findLikedPostIds);
        Set<Long> bookmarkedByMe = viewerFlags(viewer, postIds, bookmarkRepo::findBookmarkedPostIds);

        Map<Long, CommunityAuthorResponse> authors = authorsFor(posts);

        return post -> {
            List<BrandingPostMedia> media = mediaByPost.getOrDefault(post.getId(), List.of());
            return CommunityPostCardResponse.builder()
                    .id(post.getId())
                    .category(post.getCategory())
                    .title(post.getTitle())
                    .bodyExcerpt(excerpt(post.getCaption()))
                    .author(authors.get(post.getId()))
                    .thumbnailUrls(media.stream().limit(CARD_THUMBNAIL_COUNT)
                            .map(BrandingPostMedia::getUrl).collect(Collectors.toList()))
                    .mediaCount(media.size())
                    .locationLabel(post.getLocationLabel())
                    .createdAt(post.getCreatedAt())
                    .likeCount(likes.getOrDefault(post.getId(), 0L))
                    .commentCount(comments.getOrDefault(post.getId(), 0L))
                    .bookmarkCount(bookmarks.getOrDefault(post.getId(), 0L))
                    .likedByMe(likedByMe.contains(post.getId()))
                    .bookmarkedByMe(bookmarkedByMe.contains(post.getId()))
                    .hidden(post.isHidden())
                    .linkedCourse(toLinkedCourse(post.getLinkedCourse()))
                    .match(toMatch(matches.get(post.getId())))
                    .build();
        };
    }

    private CommunityPostDetailResponse toDetail(BrandingPost post, Account viewer) {
        Long postId = post.getId();
        Account author = post.getBranding().getAccount();
        boolean mine = viewer != null && Objects.equals(author.getId(), viewer.getId());

        Set<Long> liked = viewerFlags(viewer, List.of(postId), likeRepo::findLikedPostIds);
        Set<Long> bookmarked = viewerFlags(viewer, List.of(postId), bookmarkRepo::findBookmarkedPostIds);

        return CommunityPostDetailResponse.builder()
                .id(postId)
                .category(post.getCategory())
                .title(post.getTitle())
                .body(post.getCaption())
                .author(authorsFor(List.of(post)).get(postId))
                .media(post.getMedia().stream()
                        .map(m -> CommunityPostDetailResponse.Media.builder()
                                .url(m.getUrl()).sortOrder(m.getSortOrder()).build())
                        .collect(Collectors.toList()))
                .tags(post.getTags().stream().map(BrandingPostTag::getTag).collect(Collectors.toList()))
                .locationLabel(post.getLocationLabel())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .likeCount(likeRepo.countByPostId(postId))
                .commentCount(commentRepo.countByPostIdAndIsDeletedFalse(postId))
                .bookmarkCount(bookmarkRepo.countByPostId(postId))
                .likedByMe(liked.contains(postId))
                .bookmarkedByMe(bookmarked.contains(postId))
                .hidden(post.isHidden())
                .mine(mine)
                // 오너에게는 DRAFT 도 실어준다 — 아래 toLinkedCourseForOwner 의 Javadoc 참고.
                .linkedCourse(mine
                        ? toLinkedCourseForOwner(post.getLinkedCourse())
                        : toLinkedCourse(post.getLinkedCourse()))
                .match(toMatch(matchRepo.findById(postId).orElse(null)))
                .build();
    }

    /**
     * 작성자 합성 — 강사 여부와 공개 강의 수를 <b>페이지 전체에 대해 각각 한 번씩</b> 조회한다.
     *
     * <p>강의 수는 브랜딩의 {@code products.lessons} 와 같은 규칙을 따른다 —
     * {@code showSeededCourses} 설정까지 동일하게 적용해서 같은 강사의 프로필 강의 수와 커뮤니티 칩
     * 숫자가 어긋나지 않게 한다.
     */
    private Map<Long, CommunityAuthorResponse> authorsFor(List<BrandingPost> posts) {
        Map<Long, Account> authorByPost = posts.stream()
                .collect(Collectors.toMap(BrandingPost::getId, p -> p.getBranding().getAccount(), (a, b) -> a));
        Map<Long, CommunityAuthorResponse> byAccount = authorComposer.compose(authorByPost.values());

        Map<Long, CommunityAuthorResponse> byPost = new HashMap<>();
        authorByPost.forEach((postId, account) -> byPost.put(postId, byAccount.get(account.getId())));
        return byPost;
    }

    private Map<Long, List<BrandingPostMedia>> mediaByPost(List<Long> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return mediaRepo.findAllByPostIds(postIds).stream()
                .collect(Collectors.groupingBy(m -> m.getPost().getId()));
    }

    /** {@code [id, count]} 행들을 맵으로. 행이 없는 id 는 호출부가 기본값 0 으로 읽는다. */
    private Map<Long, Long> countMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    /** 비로그인이면 조회 자체를 하지 않는다 — 빈 집합이면 모든 "내 반응" 이 false 가 된다. */
    private Set<Long> viewerFlags(Account viewer, List<Long> ids,
                                  java.util.function.BiFunction<Long, Collection<Long>, List<Long>> query) {
        if (viewer == null || ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(query.apply(viewer.getId(), ids));
    }

    private CommunityMatchResponse toMatch(CommunityPostMatch match) {
        if (match == null) {
            return null;
        }
        return CommunityMatchResponse.builder()
                .meetDate(match.getMeetDate())
                .meetTime(match.getMeetTime())
                .capacity(match.getCapacity())
                .levelLabel(match.getLevelLabel())
                .open(match.isOpen())
                .build();
    }

    /**
     * <b>오너 전용</b> — DRAFT 도 그대로 싣는다.
     *
     * <p>공개 규칙({@link #toLinkedCourse})은 "비공개 코스가 <b>공개 화면</b>에 새면 안 된다" 인데,
     * 이걸 오너 본인의 상세에까지 적용했더니 <b>수정 시 연결이 조용히 끊기는 경로</b>가 됐다:
     * 상세에 키가 없으니 수정 폼이 {@code linkedCourseId} 를 프리필하지 못하고, 스냅샷 교체라
     * 저장하는 순간 연결이 사라진다. 사용자는 제목만 고쳤는데 아무 에러도 없다.
     *
     * <p><b>클라이언트가 방어할 수 없는 종류다</b> — "사용자가 연결을 뗐다" 와 "응답에 안 실려서 모른다"
     * 가 요청에서 똑같이 {@code linkedCourseId == null} 로 보인다. 게다가 이건 우리가 <b>권장하는</b>
     * 사용이다(준비 중인 강의를 미리 걸어두고 공개되면 뜨게 — 앱 피커가 "비공개 · 공개 후 노출" 로 안내한다).
     *
     * <p>오너는 자기 DRAFT 코스의 존재를 이미 알기 때문에 노출이 아니다. <b>피드 카드는 바꾸지 않는다</b>
     * — 카드에는 오너 개념이 없고 공개 목록이라 기존 규칙 그대로다.
     */
    private LinkedCourseResponse toLinkedCourseForOwner(Course course) {
        return toLinkedCourse(course, true);
    }

    /** DRAFT·삭제된 코스는 <b>키 자체를 생략</b>한다 — 비공개 코스가 공개 화면에 새면 안 된다. */
    private LinkedCourseResponse toLinkedCourse(Course course) {
        return toLinkedCourse(course, false);
    }

    /**
     * 매핑은 한 곳에만 둔다 — 오너용·공개용을 각각 복사해두면 {@link LinkedCourseResponse} 에 필드가
     * 늘어난 날 한쪽만 고쳐지고, 그 차이는 "오너에게만 필드가 빠진다" 로 나타나 눈에 잘 안 띈다.
     */
    private LinkedCourseResponse toLinkedCourse(Course course, boolean includeDraft) {
        if (course == null || (!includeDraft && course.getStatus() == CourseStatus.DRAFT)) {
            return null;
        }
        return LinkedCourseResponse.builder()
                .id(course.getId())
                .title(course.getTitle())
                .thumbnailUrl(course.getMedia().isEmpty() ? null : course.getMedia().get(0).getUrl())
                .price(course.getPrice())
                .status(course.getStatus())
                .build();
    }

    /* ─── 내부: 공통 ─────────────────────────────────────── */

    /** 최신순 + id tie-break — 같은 초에 만들어진 글이 페이지 경계에서 중복·누락되지 않게. */
    private Pageable withLatestSort(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private BrandingPost requireMine(Long postId, Long accountId) {
        return postRepo.findMine(postId, accountId).orElseThrow(ResourceNotFoundException::new);
    }

    private Account loadAccount(Account currentUser) {
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }

    private String avatarUrlOf(Account account) {
        ProfilePhoto photo = account.getProfilePhoto();
        return photo == null ? null : photo.getImageUrl();
    }

    private String excerpt(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        return body.length() <= EXCERPT_LENGTH ? body : body.substring(0, EXCERPT_LENGTH);
    }

    private List<String> urlsOf(BrandingPost post) {
        return post.getMedia().stream().map(BrandingPostMedia::getUrl).collect(Collectors.toList());
    }

    /** S3 삭제 실패가 글 삭제를 막지는 않는다 — 고아 객체 하나가 데이터가 안 지워지는 것보다 낫다. */
    private void deleteObjectsQuietly(List<String> urls) {
        for (String url : urls) {
            try {
                s3Uploader.deletePublicObject(url);
            } catch (RuntimeException e) {
                log.warn("[community] 사진 삭제 실패(계속 진행) url={}", url, e);
            }
        }
    }
}
