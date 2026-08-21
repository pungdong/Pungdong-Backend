package com.diving.pungdong.branding;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.block.BlockService;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.account.ProfilePhoto;
import com.diving.pungdong.branding.dto.*;
import com.diving.pungdong.course.Course;
import com.diving.pungdong.course.CourseJpaRepo;
import com.diving.pungdong.course.CourseStatus;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.validation.PublicMediaUrlPolicy;
import com.diving.pungdong.global.storage.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 브랜딩 게시물 — 공개 그리드·상세 + 오너 CRUD.
 *
 * <p>게시물이 프로필 본체와 다른 서비스인 이유: {@link BrandingService} 는 "프로필 한 장"의 합성이고
 * 여기는 목록·페이지네이션·미디어 lifecycle 이라 관심사가 다르다. 둘 다 같은 {@code branding} 패키지에
 * 있어 크로스 도메인 의존은 늘지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandingPostService {

    /** 한 번에 가져갈 수 있는 최대 개수 — 클라이언트가 size 를 키워 전수 스크래핑하는 걸 막는다. */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 18;

    private final BrandingPostJpaRepo postRepo;
    private final BrandingPostMediaJpaRepo mediaRepo;
    private final AccountBrandingJpaRepo brandingRepo;
    private final AccountJpaRepo accountRepo;
    private final CourseJpaRepo courseRepo;
    private final S3Uploader s3Uploader;

    /** 본문에 실린 이미지 URL 이 우리가 발급한 것인지 검사한다. 커뮤니티 글과 같은 규칙을 공유한다. */
    private final PublicMediaUrlPolicy mediaUrlPolicy;
    /** 공개 그리드·상세의 차단 판정. */
    private final BlockService blockService;

    /* ─── 공개 ───────────────────────────────────────────── */

    /** 공개 그리드 — 숨김 제외, 고정 먼저 최신순. 정렬·크기는 서버가 고정한다. */
    public Page<BrandingPostCardResponse> publicGrid(String nickName, Pageable pageable, Account viewer) {
        AccountBranding branding = brandingRepo.findPublishedByNickName(nickName).stream()
                .findFirst()
                .orElseThrow(ResourceNotFoundException::new);

        // 프로필 응답(BrandingService.publicProfile)과 같은 규칙: 상대가 나를 차단했으면 없는 것처럼,
        // 내가 차단했으면 프로필은 열되 그리드는 비운다(해제 동선을 남긴다).
        Long ownerId = branding.getAccount().getId();
        if (viewer != null && !viewer.getId().equals(ownerId)) {
            if (blockService.hasBlocked(ownerId, viewer.getId())) {
                throw new ResourceNotFoundException();
            }
            if (blockService.hasBlocked(viewer.getId(), ownerId)) {
                return Page.empty(fixedPage(pageable));
            }
        }

        Page<BrandingPost> posts = postRepo.findPublicGrid(branding.getId(), fixedPage(pageable));
        return toCards(posts, false);
    }

    /** 오너 그리드 — 숨김 포함. 숨긴 글을 다시 켜려면 보여야 한다. */
    public Page<BrandingPostCardResponse> myGrid(Account currentUser, Pageable pageable) {
        Optional<AccountBranding> branding = brandingRepo.findByAccountId(currentUser.getId());
        if (branding.isEmpty()) {
            return Page.empty(fixedPage(pageable)); // 프로필이 아직 없으면 빈 페이지(400 아님)
        }
        Page<BrandingPost> posts = postRepo.findOwnerGrid(branding.get().getId(), fixedPage(pageable));
        return toCards(posts, true);
    }

    /**
     * 게시물 상세. <b>오너는 자기 글이면 숨김·미발행이어도 볼 수 있다</b> — 상세 화면에서 바로 "다시 공개"를
     * 누르는 경로가 그 예외를 전제하기 때문이다(숨긴 순간 상세가 오너에게도 막히면 되돌릴 화면이 없다).
     *
     * <p>그 외에는 발행 + 미숨김 + 미탈퇴만 보인다. 안 보이면 403 이 아니라 <b>400(존재 숨김)</b>.
     *
     * @param viewer 비로그인이면 {@code null} — 이 엔드포인트는 permitAll 이라 인증이 없을 수 있다.
     */
    public BrandingPostDetailResponse detail(Long postId, Account viewer) {
        BrandingPost post = postRepo.findById(postId).orElseThrow(ResourceNotFoundException::new);
        if (!isVisibleTo(post, viewer)) {
            throw new ResourceNotFoundException();
        }
        // 차단 관계면 여기로도 열리지 않는다 — 그리드만 비우면 상세 URL 이 우회로가 된다.
        if (viewer != null
                && blockService.isBlockedBetween(viewer.getId(), post.getBranding().getAccount().getId())) {
            throw new ResourceNotFoundException();
        }
        return toDetail(post, isOwner(post, viewer));
    }

    private boolean isOwner(BrandingPost post, Account viewer) {
        return viewer != null
                && Objects.equals(post.getBranding().getAccount().getId(), viewer.getId());
    }

    /**
     * <b>프로필 글이 아니면 이 경로로는 아무에게도 안 보인다</b> — 오너에게도. 커뮤니티에만 올린 글은
     * 브랜딩 상세({@code GET /branding-posts/{id}})의 대상이 아니다. 커뮤니티 글은
     * {@code GET /community/posts/{id}} 로 본다. 안 걸면 프로필에 없는 글이 프로필 URL 로 열려
     * "브랜딩 → 커뮤니티 단방향" 이 조회 쪽에서 뚫린다.
     */
    private boolean isVisibleTo(BrandingPost post, Account viewer) {
        if (!post.isShowOnProfile()) {
            return false;
        }
        AccountBranding branding = post.getBranding();
        Account owner = branding.getAccount();
        if (viewer != null && Objects.equals(owner.getId(), viewer.getId())) {
            return true; // 오너는 자기 프로필 글을 항상 본다(숨김·미발행 포함)
        }
        return branding.isPublished()
                && !post.isHidden()
                && !Boolean.TRUE.equals(owner.getIsDeleted());
    }

    /* ─── 오너 CRUD ──────────────────────────────────────── */

    /** 작성 — <b>프로필이 없으면 여기서 만든다(upsert)</b>. 디자인상 첫 진입점이 게시물 작성이라 주 경로다. */
    @Transactional
    public BrandingPostDetailResponse create(Account currentUser, BrandingPostRequest request) {
        Account owner = loadAccount(currentUser);
        AccountBranding branding = brandingRepo.findByAccountId(owner.getId())
                .orElseGet(() -> brandingRepo.save(AccountBranding.builder()
                        .account(owner).isPublished(true).build()));

        // 구버전 앱 호환 경로다 — 여기로 온 글은 통합 폼의 showOnProfile=true 와 같은 상태가 된다
        // (프로필 그리드 + 커뮤니티 피드). 통합 폼에서는 작성자가 고르는 값이지만 이 경로엔 그 필드가
        // 없으므로 예전 의미대로 고정한다. DB DEFAULT 는 기존 행 backfill 용이지 신규 쓰기용이 아니다.
        BrandingPost post = BrandingPost.builder()
                .branding(branding)
                .showOnProfile(true)
                .showInFeed(true)
                .build();
        apply(post, request, owner);
        return toDetail(postRepo.save(post), true);
    }

    /** 수정 — 미디어·태그를 스냅샷으로 교체한다. */
    @Transactional
    public BrandingPostDetailResponse update(Account currentUser, Long postId, BrandingPostRequest request) {
        Account owner = loadAccount(currentUser);
        BrandingPost post = requireMine(postId, owner.getId());

        List<String> removed = urlsOf(post);
        apply(post, request, owner);
        // 교체로 빠진 사진은 아무도 참조하지 않는다 — 안 지우면 S3 고아로 쌓인다.
        removed.removeAll(request.getMediaUrls());
        deleteObjectsQuietly(removed);

        return toDetail(post, true);
    }

    /** 삭제 — 행과 함께 S3 객체도 지운다. */
    @Transactional
    public void delete(Account currentUser, Long postId) {
        BrandingPost post = requireMine(postId, currentUser.getId());
        List<String> urls = urlsOf(post);
        postRepo.delete(post);
        deleteObjectsQuietly(urls);
    }

    /**
     * 상단 고정 — <b>프로필 그리드에만 있는 개념</b>이라 여기 남는다(피드 정렬은 서버 고정).
     *
     * <p>짝이던 {@code updateHidden} 은 없앴다: 숨김은 커뮤니티·브랜딩 양쪽에 걸리는 전역 스위치인데
     * 이 문은 {@code showOnProfile=true} 인 글만 통과시켜 커뮤니티 전용 글을 못 숨겼고, 어드민
     * 조치(ACTIONED) 확인이 없어 신고로 내려간 글을 작성자가 되살릴 수 있었다. 숨김의 단일 경로는
     * {@code PATCH /community/posts/{id}/visibility} 다.
     */
    @Transactional
    public BrandingPostDetailResponse updatePinned(Account currentUser, Long postId, boolean pinned) {
        BrandingPost post = requireMine(postId, currentUser.getId());
        post.setPinned(pinned);
        return toDetail(post, true);
    }

    /* ─── 내부 ───────────────────────────────────────────── */

    /**
     * 소유권 검증 — 남의 글이면 403 이 아니라 <b>400(존재 숨김)</b>. 존재 여부 자체를 알려주지 않는다
     * (레포의 anti-IDOR 관례).
     */
    private BrandingPost requireMine(Long postId, Long accountId) {
        return postRepo.findMine(postId, accountId).orElseThrow(ResourceNotFoundException::new);
    }

    private void apply(BrandingPost post, BrandingPostRequest request, Account owner) {
        request.getMediaUrls().forEach(this::requireOurCdnUrl);

        post.setCategory(request.getCategory());
        post.setTitle(request.getTitle());
        post.setCaption(request.getCaption());
        post.setLocationLabel(request.getLocationLabel());

        List<BrandingPostMedia> media = new ArrayList<>();
        for (int i = 0; i < request.getMediaUrls().size(); i++) {
            media.add(BrandingPostMedia.builder()
                    // 영상은 스키마 자리만 예약돼 있고 업로드 경로가 없다 — 지금 저장되는 건 전부 사진이다.
                    .kind(BrandingMediaKind.PHOTO)
                    .url(request.getMediaUrls().get(i))
                    .sortOrder(i)
                    .build());
        }
        post.replaceMedia(media);

        post.replaceTags(BrandingPostTag.normalize(request.getTags()));

        post.setLinkedCourse(resolveLinkedCourse(request.getLinkedCourseId(), owner));
    }

    /** 연결 강의는 <b>내 코스</b>여야 한다. 남의 코스를 붙여 홍보하는 걸 막는다. */
    private Course resolveLinkedCourse(Long courseId, Account owner) {
        if (courseId == null) {
            return null;
        }
        Course course = courseRepo.findById(courseId).orElseThrow(BadRequestException::new);
        if (course.getInstructor() == null || !Objects.equals(course.getInstructor().getId(), owner.getId())) {
            throw new BadRequestException("내 강의만 연결할 수 있어요.");
        }
        return course;
    }

    /**
     * 우리 CDN(또는 로컬 stub) 이 발급한 URL 인지 확인한다.
     *
     * <p>규칙 자체는 {@link PublicMediaUrlPolicy} 로 옮겼다 — 커뮤니티 글도 같은 공개 버킷·같은 업로드
     * 엔드포인트를 쓰므로 검사가 두 벌이면 한쪽만 고쳐지는 순간 갈라진다.
     */
    private void requireOurCdnUrl(String url) {
        mediaUrlPolicy.requireOurs(url);
    }

    private List<String> urlsOf(BrandingPost post) {
        return post.getMedia().stream().map(BrandingPostMedia::getUrl).collect(Collectors.toList());
    }

    /** 사진 삭제 실패가 게시물 삭제를 막지 않게 한다 — 고아 1개가 남는 게 데이터가 안 지워지는 것보다 낫다. */
    private void deleteObjectsQuietly(List<String> urls) {
        urls.forEach(url -> {
            try {
                s3Uploader.deletePublicObject(url);
            } catch (RuntimeException e) {
                log.warn("[branding-post] 사진 삭제 실패(계속 진행) url={}", url, e);
            }
        });
    }

    /** 클라이언트가 보낸 정렬은 버리고 크기만 상한 안에서 받는다(정렬은 쿼리가 고정). */
    private Pageable fixedPage(Pageable pageable) {
        int size = pageable.isPaged() ? Math.min(pageable.getPageSize(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        return PageRequest.of(page, size);
    }

    /** 카드 매핑 — 미디어를 <b>한 번에</b> 모아 그룹핑한다(카드마다 LAZY 접근하면 N+1). */
    private Page<BrandingPostCardResponse> toCards(Page<BrandingPost> posts, boolean includeHiddenFlag) {
        List<Long> ids = posts.getContent().stream().map(BrandingPost::getId).collect(Collectors.toList());
        Map<Long, List<BrandingPostMedia>> mediaByPost = ids.isEmpty() ? Map.of()
                : mediaRepo.findAllByPostIds(ids).stream()
                .collect(Collectors.groupingBy(m -> m.getPost().getId()));

        return posts.map(post -> {
            List<BrandingPostMedia> media = mediaByPost.getOrDefault(post.getId(), List.of());
            return BrandingPostCardResponse.builder()
                    .id(post.getId())
                    .thumbnailUrl(media.isEmpty() ? null : media.get(0).getUrl())
                    .mediaCount(media.size())
                    .pinned(post.isPinned())
                    .hidden(includeHiddenFlag ? post.isHidden() : null)
                    .build();
        });
    }

    /**
     * @param owner 뷰어가 작성자 본인인가. <b>연결 강의의 DRAFT 노출 여부만</b> 이 값에 달려 있다
     *              ({@link #toLinkedCourse}). 오너 CRUD 경로는 {@code requireMine} 을 이미 통과했으므로
     *              무조건 {@code true} 다.
     */
    private BrandingPostDetailResponse toDetail(BrandingPost post, boolean owner) {
        Account author = post.getBranding().getAccount();
        return BrandingPostDetailResponse.builder()
                .id(post.getId())
                .author(BrandingPostDetailResponse.Author.builder()
                        .nickName(author.getNickName())
                        .avatarUrl(avatarUrlOf(author))
                        .build())
                .media(post.getMedia().stream()
                        .map(m -> BrandingPostDetailResponse.Media.builder()
                                .kind(m.getKind()).url(m.getUrl()).sortOrder(m.getSortOrder()).build())
                        .collect(Collectors.toList()))
                // 수정 폼이 되실어야 하는 값이다 — 안 주면 저장할 때마다 지워진다(DTO Javadoc 참고).
                .category(post.getCategory())
                .title(post.getTitle())
                .caption(post.getCaption())
                .tags(post.getTags().stream().map(BrandingPostTag::getTag).collect(Collectors.toList()))
                .locationLabel(post.getLocationLabel())
                .createdAt(post.getCreatedAt())
                .pinned(post.isPinned())
                .hidden(post.isHidden())
                .linkedCourse(toLinkedCourse(post.getLinkedCourse(), owner))
                .build();
    }

    /**
     * DRAFT(미공개)·삭제된 코스는 <b>공개 응답에서</b> 안 내려준다 — 공개 페이지에 미공개 코스가 새면 안 된다.
     *
     * <p><b>단 오너 본인에게는 DRAFT 도 싣는다.</b> 이 상세({@code GET /branding-posts/{id}})가 오너의
     * <b>수정 폼 프리필 소스</b>이고 수정은 스냅샷 교체다 — 키가 없으면 폼이 {@code linkedCourseId} 를
     * 못 채우고, 저장하는 순간 <b>연결이 조용히 끊긴다</b>. 사용자는 오타만 고쳤는데 아무 에러도 없다.
     * 요청에서 "사용자가 뗐다" 와 "응답에 안 실려서 모른다" 가 구별되지 않아 클라이언트가 방어할 수도 없다.
     *
     * <p>커뮤니티가 같은 이유로 먼저 고쳤고({@code CommunityPostService.toLinkedCourse}), 같은 성격의
     * 결정을 도메인마다 다르게 갈 이유가 없다. 오너는 자기 DRAFT 코스를 이미 알기 때문에 노출이 아니다.
     * <b>그리드 카드는 바꾸지 않는다</b> — 카드엔 오너 개념이 없다.
     */
    private LinkedCourseResponse toLinkedCourse(Course course, boolean owner) {
        // 어드민이 조치한 강의는 오너에게도 카드로 내보내지 않는다 — DRAFT 와 달리 "아직 안 연 것" 이
        // 아니라 "내려간 것" 이라, 클릭하면 공개 상세가 400 인 죽은 카드가 된다.
        if (course == null || course.isBlocked() || (!owner && course.getStatus() == CourseStatus.DRAFT)) {
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

    private Account loadAccount(Account currentUser) {
        return accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);
    }

    private String avatarUrlOf(Account account) {
        return ProfilePhoto.displayUrlOf(account);
    }
}
