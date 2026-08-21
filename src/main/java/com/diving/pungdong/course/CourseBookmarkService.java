package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.AccountJpaRepo;
import com.diving.pungdong.course.dto.CourseBookmarkResponse;
import com.diving.pungdong.global.advice.exception.ResourceNotFoundException;
import com.diving.pungdong.global.persistence.IdempotentInsert;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강의 저장(북마크) 설정·해제. 커뮤니티 {@code CommunityReactionService} 의 북마크 부분을 그대로 옮긴 것 —
 * 새로 설계한 게 없다.
 *
 * <p><b>토글이 아니라 설정/해제 두 메서드이고, 둘 다 멱등이다.</b> 토글 하나로 두면 재시도·연타의 결과가
 * 요청 순서에 달려 있어(짝수면 꺼짐) 낙관적 업데이트와 어긋난다. POST 는 "저장된 상태로 만들어라",
 * DELETE 는 "저장 안 된 상태로 만들어라" 라 몇 번 불러도 결과가 같다. 마커 테이블의
 * {@code (course, account)} UNIQUE 가 그걸 DB 에서 보장한다.
 *
 * <p><b>동시</b> 요청도 마찬가지다: 삽입을 {@link IdempotentInsert} 로 격리해 제약 위반이 이 트랜잭션을
 * 오염시키지 않는다(격리 없이 catch 만 하면 뒤이은 카운트 조회에서 500 이 난다 — 그 클래스 Javadoc 참고).
 *
 * <p>대상은 <b>공개 표면에 보이는 강의</b>여야 한다({@link CourseService#requirePubliclyVisible}).
 * 비OPEN·차단·미승인 강사의 강의에 저장이 걸리면 강의 id 만으로 존재를 확인하는 채널이 된다(anti-IDOR).
 * 커뮤니티가 숨김 글에 반응을 400 으로 막는 것과 같은 판단이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseBookmarkService {

    private final CourseBookmarkJpaRepo bookmarkRepo;
    private final CourseService courseService;
    private final AccountJpaRepo accountRepo;
    /** 제약 위반이 이 트랜잭션을 오염시키지 않도록 삽입만 새 트랜잭션에서 돌린다. */
    private final IdempotentInsert idempotentInsert;

    @Transactional
    public CourseBookmarkResponse bookmark(Account currentUser, Long courseId) {
        Course course = courseService.requirePubliclyVisible(courseId);
        Account me = accountRepo.findById(currentUser.getId()).orElseThrow(ResourceNotFoundException::new);

        // 이미 있으면 새로 만들지 않는다. 그럼에도 동시에 두 요청이 들어오면 UNIQUE 가 최종 방어선이라
        // 제약 위반을 "이미 저장된 상태" 로 흡수한다 — 사용자 입장에서 결과가 같으니 에러가 아니다.
        if (bookmarkRepo.findByCourseIdAndAccountId(courseId, me.getId()).isEmpty()) {
            try {
                idempotentInsert.insert(bookmarkRepo,
                        CourseBookmark.builder().course(course).account(me).build());
            } catch (DataIntegrityViolationException alreadyBookmarked) {
                // no-op — 경쟁 요청이 먼저 넣었다. 결과가 같으니 에러가 아니다.
            }
        }
        // 삽입은 새 트랜잭션에서 커밋됐다 — 이 트랜잭션의 스냅샷(REPEATABLE READ)엔 안 보이므로 카운트도
        // 새 스냅샷으로 읽는다. 안 그러면 응답 count 가 "내 것 빠진 값" 이 된다(IdempotentInsert 참조).
        return CourseBookmarkResponse.builder()
                .count(idempotentInsert.countFresh(() -> bookmarkRepo.countByCourseId(courseId)))
                .active(true)
                .build();
    }

    @Transactional
    public CourseBookmarkResponse unbookmark(Account currentUser, Long courseId) {
        courseService.requirePubliclyVisible(courseId);
        bookmarkRepo.findByCourseIdAndAccountId(courseId, currentUser.getId())
                .ifPresent(bookmarkRepo::delete);
        // 제거는 이 트랜잭션 자신이 했으니 스냅샷에 보인다 — 여기선 countFresh 가 필요 없다.
        return CourseBookmarkResponse.builder()
                .count(bookmarkRepo.countByCourseId(courseId))
                .active(false)
                .build();
    }
}
