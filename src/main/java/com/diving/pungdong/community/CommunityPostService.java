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
import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 카드 그리드가 3장 + "+N" 오버레이 구조라 앞 3장만 싣는다. */
    private static final int CARD_THUMBNAIL_COUNT = 3;

    /** 카드 본문 미리보기 길이. FE 가 CSS 로 3줄 클램프를 거니 넉넉해야 줄이 꽉 찬다. */
    private static final int EXCERPT_LENGTH = 200;

    private final CommunityPostJpaRepo postRepo;
    private final CommunityPostMatchJpaRepo matchRepo;
    private final CommunityPostLikeJpaRepo likeRepo;
    private final CommunityPostBookmarkJpaRepo bookmarkRepo;
    private final CommunityCommentJpaRepo commentRepo;
    private final BrandingPostMediaJpaRepo mediaRepo;
    private final AccountBrandingJpaRepo brandingRepo;
    private final AccountJpaRepo accountRepo;
    private final CourseJpaRepo courseRepo;
    private final InstructorApplicationJpaRepo applicationRepo;
    private final SiteSettingsProvider siteSettings;
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
                                                boolean bookmarkedByMe,
                                                Account viewer,
                                                Pageable pageable) {
        Pageable page = fixedPage(pageable);

        Page<BrandingPost> posts;
        if (category == CommunityCategory.MATCH && !bookmarkedByMe) {
            posts = postRepo.findMatchFeed(page);
        } else {
            Specification<BrandingPost> spec = Specification.where(CommunityPostSpecifications.feedVisible())
                    .and(CommunityPostSpecifications.category(category))
                    .and(bookmarkedByMe ? CommunityPostSpecifications.bookmarkedBy(requireViewer(viewer).getId()) : null);
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
     * 삭제 — 게시물은 hard delete 다(댓글만 soft delete). 딸린 반응·댓글·모집정보를 FK 순서대로 먼저 지운다.
     */
    @Transactional
    public void delete(Account currentUser, Long postId) {
        BrandingPost post = requireMine(postId, currentUser.getId());
        List<String> urls = urlsOf(post);

        likeRepo.deleteByPostId(postId);
        bookmarkRepo.deleteByPostId(postId);
        matchRepo.deleteByPostId(postId);
        postRepo.delete(post);

        deleteObjectsQuietly(urls);
    }

    /** 숨김 토글 — 삭제가 아니라 <b>되돌릴 수 있는</b> 상태다. */
    @Transactional
    public CommunityPostDetailResponse updateHidden(Account currentUser, Long postId, boolean hidden) {
        BrandingPost post = requireMine(postId, currentUser.getId());
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
        match.setMeetDate(input.getMeetDate());
        match.setMeetTime(input.getMeetTime());
        match.setCapacity(input.getCapacity());
        match.setLevelLabel(input.getLevelLabel());
        matchRepo.save(match);
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
                .linkedCourse(toLinkedCourse(post.getLinkedCourse()))
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
        Set<Long> accountIds = authorByPost.values().stream()
                .map(Account::getId).collect(Collectors.toSet());
        if (accountIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> instructorIds = applicationRepo
                .findByAccountIdInAndStatus(accountIds, InstructorApplicationStatus.APPROVED).stream()
                .map(application -> application.getAccount().getId())
                .collect(Collectors.toSet());

        Map<Long, Long> lessonCounts = instructorIds.isEmpty()
                ? Map.of()
                : countMap(siteSettings.current().showSeededCourses()
                        ? courseRepo.countByInstructorIdsAndStatus(instructorIds, CourseStatus.OPEN)
                        : courseRepo.countByInstructorIdsAndStatusExcludingSeeded(instructorIds, CourseStatus.OPEN));

        Map<Long, CommunityAuthorResponse> result = new HashMap<>();
        authorByPost.forEach((postId, account) -> {
            boolean isInstructor = instructorIds.contains(account.getId());
            result.put(postId, CommunityAuthorResponse.builder()
                    .nickName(account.getNickName())
                    .avatarUrl(avatarUrlOf(account))
                    .isInstructor(isInstructor)
                    // 강사가 아니면 키 자체를 생략한다 — 0 을 내려주면 "강의 0개인 강사" 로 읽힌다.
                    .lessonCount(isInstructor
                            ? (int) (long) lessonCounts.getOrDefault(account.getId(), 0L)
                            : null)
                    .build());
        });
        return result;
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

    /** DRAFT·삭제된 코스는 <b>키 자체를 생략</b>한다 — 비공개 코스가 공개 화면에 새면 안 된다. */
    private LinkedCourseResponse toLinkedCourse(Course course) {
        if (course == null || course.getStatus() == CourseStatus.DRAFT) {
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

    /**
     * 클라이언트 정렬을 <b>버리고</b> 페이지 번호·크기만 취한다. 임의 필드 정렬을 태우면 내부 컬럼을
     * 탐색하거나 인덱스 없는 정렬로 풀스캔을 유발할 수 있다.
     */
    private Pageable fixedPage(Pageable pageable) {
        int size = pageable.isPaged() ? Math.min(pageable.getPageSize(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        return PageRequest.of(page, size);
    }

    /** 최신순 + id tie-break — 같은 초에 만들어진 글이 페이지 경계에서 중복·누락되지 않게. */
    private Pageable withLatestSort(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private BrandingPost requireMine(Long postId, Long accountId) {
        return postRepo.findMine(postId, accountId).orElseThrow(ResourceNotFoundException::new);
    }

    private Account requireViewer(Account viewer) {
        if (viewer == null) {
            throw new ResourceNotFoundException();
        }
        return viewer;
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
