package com.diving.pungdong.block;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 차단 관계 조회.
 *
 * <p><b>피드·댓글 필터는 이 레포를 거치지 않는다.</b> 목록을 메모리로 가져와 걸러내면 페이지가 짧아지고
 * {@code totalElements} 가 거짓이 된다 — 필터는 각 조회 쿼리 안의 {@code exists} 서브쿼리로 들어간다
 * ({@code CommunityPostJpaRepo.BLOCK_FILTER} 참고). 여기 있는 메서드는 <b>단건 판정</b>(상세·프로필)과
 * <b>차단 관리 화면</b>이 쓴다.
 */
public interface AccountBlockJpaRepo extends JpaRepository<AccountBlock, Long> {

    /** 내가 저 사람을 차단했나 — 해제·중복 차단 판정. */
    Optional<AccountBlock> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    /**
     * 두 계정 사이에 <b>어느 방향으로든</b> 차단이 있나. 상세·프로필처럼 목록이 아닌 단건 경로가 쓴다.
     *
     * <p>방향을 합치는 게 요점이다 — 내가 차단했든 상대가 차단했든 그 콘텐츠는 내게 보이지 않아야 한다.
     * 다만 <b>호출부가 응답을 나눠야 하는 경우</b>(프로필: 내가 차단 → 해제 버튼 / 상대가 차단 → 없는 것처럼)
     * 는 이걸 쓰지 말고 {@link #existsByBlockerIdAndBlockedId} 를 방향별로 두 번 부른다.
     */
    @Query("select count(b) > 0 from AccountBlock b "
            + "where (b.blocker.id = :one and b.blocked.id = :other) "
            + "or (b.blocker.id = :other and b.blocked.id = :one)")
    boolean existsBetween(@Param("one") Long one, @Param("other") Long other);

    /**
     * 뷰어와 차단 관계(양방향)인 <b>상대 계정 id</b> 전부. 목록을 메모리에서 걸러야 하는 경로
     * (댓글 스레드·추천 강사)만 쓴다 — 페이징이 없어 개수가 어긋날 여지가 없는 곳들이다.
     */
    @Query("select case when b.blocker.id = :viewerId then b.blocked.id else b.blocker.id end "
            + "from AccountBlock b where b.blocker.id = :viewerId or b.blocked.id = :viewerId")
    List<Long> findRelatedAccountIds(@Param("viewerId") Long viewerId);

    /** 차단 관리 화면 — 내가 차단한 사람 목록. 최근에 차단한 순. */
    @Query("select b from AccountBlock b where b.blocker.id = :blockerId "
            + "order by b.createdAt desc, b.id desc")
    Page<AccountBlock> findMine(@Param("blockerId") Long blockerId, Pageable pageable);
}
