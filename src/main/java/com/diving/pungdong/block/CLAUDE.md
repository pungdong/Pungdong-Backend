# CLAUDE.md — block (유저 차단)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **정책 단일 출처는 [docs/features/moderation.md](../../../../../../../docs/features/moderation.md).**
> 구현 지도는 [docs/architecture/block.md](../../../../../../../docs/architecture/block.md).

> **package-by-feature.** 이 패키지는 **`account` 만** 단방향 참조한다. `community`·`branding` 이
> 이쪽을 참조하지, 이쪽이 그들을 참조하지 않는다 — 반대로 만들면 순환이 된다.

## 가장 먼저 알아야 할 것 — 필터는 쿼리 안에서 건다

**차단 대상 id 를 받아 목록을 메모리에서 걸러내지 말 것.** 페이지가 짧아지고 `totalElements` 가
거짓이 된다(20개 페이지가 17개가 되고, 한 페이지가 통째로 차단이면 빈 페이지가 나와 무한스크롤이 멈춘다).

| 상황 | 방법 |
|---|---|
| **페이징되는 조회** (피드·그리드) | 쿼리 안의 `exists` 서브쿼리 — `CommunityPostJpaRepo.BLOCK_FILTER`, `CommunityPostSpecifications.notBlockedFor`, `CommunityCommentJpaRepo.NOT_BLOCKED` |
| **단건 판정** (상세·프로필·반응 가드) | `BlockService.isBlockedBetween` / `hasBlocked` |
| **페이징 없는 목록** (댓글 스레드·추천 강사) | `BlockService.relatedAccountIds` 로 메모리 필터 — 개수가 어긋날 여지가 없는 곳만 |

## 이 도메인에서 자주 틀리는 것

1. **필터를 한 경로만 거는 것.** 커뮤니티 피드는 쿼리 경로가 **셋**이다(최신순 Specification ·
   인기순 전용쿼리 · 같이가요 전용쿼리). 하나만 고치면 그 탭에서만 차단이 새어 나간다.
   ⚠️ 인기순 두 개는 **`countQuery` 가 따로** 있어 거기에도 같은 술어가 필요하다.
   새 조회 경로를 더할 때는 [architecture/block.md](../../../../../../../docs/architecture/block.md)
   §2 의 점선 목록을 먼저 확인할 것.
2. **술어는 양방향이다.** `(blocker=뷰어 and blocked=작성자) or (blocked=뷰어 and blocker=작성자)`.
   한쪽 절만 쓰면 "차단했는데 그 사람이 내 글에 계속 댓글을 단다" 가 남는다.
   ⚠️ 뒤 절은 `ix_account_block_reverse` 를 탄다 — 인덱스를 지우면 피드마다 풀스캔이 붙는다.
3. **프로필의 두 방향은 다르게 답한다.** 내가 차단 → **200 + `blockedByMe`**(유일한 해제 동선이다),
   상대가 나를 차단 → **400**(차단당한 사실을 알려주지 않는다). `isBlockedBetween` 하나로 합치면
   해제할 화면이 사라진다.
4. **댓글은 수까지 같이 맞춘다.** 스레드에서 빠진 댓글은 `commentCount` 에서도 빠져야 한다.
   차단한 사람의 댓글에 달린 **남의 답글도 함께** 빠진다(부모가 없으면 붙을 자리가 없다) —
   `NOT_BLOCKED` 의 절이 둘인 이유다.
5. **차단은 거래를 끊지 않는다.** 강의 둘러보기·수강·일정·결제·단체 채팅에 이 필터를 걸지 말 것.
   결제된 관계가 소셜 기능 때문에 깨지면 환불·분쟁 문제가 된다. `BlockUseCaseTest.B15` 가 지킨다.
6. **중복 차단은 200 멱등**(UNIQUE 가 근거), 해제는 차단돼 있지 않아도 **204**.
   기대되는 결과는 4xx 가 아니다 — 좋아요·북마크·신고와 같은 규칙.
7. **대상은 닉네임이지 계정 id 가 아니다.** 순차 id 를 계약에 노출하지 않는다(anti-IDOR).
   차단자 신원은 항상 `@CurrentUser` 에서 온다.

## 삽입은 `global/persistence/IdempotentInsert` 를 쓴다

조회와 삽입 사이에 같은 요청이 끼면 UNIQUE 가 걸리는데, 격리 없이 잡으면 트랜잭션이 rollback-only 로
오염돼 **catch 해도 결국 500** 이다. `REQUIRES_NEW` 로 가두는 이유는 그 클래스 Javadoc 과
[docs/architecture/transactions.md](../../../../../../../docs/architecture/transactions.md).
(원래 `community/` 에 있던 것을 차단이 같이 쓰게 되면서 `global/` 로 옮겼다.)
