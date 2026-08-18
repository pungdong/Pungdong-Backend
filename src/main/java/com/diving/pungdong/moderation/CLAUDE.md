# CLAUDE.md — moderation (신고·조치)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **정책 단일 출처는 [docs/features/moderation.md](../../../../../../../docs/features/moderation.md).**
> 차단(block)은 별도 패키지다 — [block/CLAUDE.md](../block/CLAUDE.md).

> **package-by-feature.** 이 패키지는 대상 도메인(`community`·`branding`·`course`·`chat`·`account`)을
> **단방향 참조**한다. 그쪽은 이 패키지를 모른다.

## 가장 먼저 알아야 할 것 — 의존은 한 방향이어야 한다

신고가 `community` 에 있던 시절, `CommunityPostService.updateHidden` 이 "어드민이 조치한 글인가" 를
**신고 테이블에서 읽었다**. 그대로 옮기면 `community → moderation → community` 순환이 된다.

**해법: 조치 사실을 대상 도메인의 컬럼에 남기고, 대상 도메인은 자기 컬럼만 본다.**

| 대상 | 조치 표식 | 그 도메인이 보는 것 |
|---|---|---|
| 게시물 | `branding_post.moderated_at` | 작성자의 공개 전환을 막을 때 이 컬럼만 |
| 강의 | `course.blocked_at` | 둘러보기·상세·집계·신규 신청에서 이 컬럼만 |
| 사용자 | `account.suspended_at` | 인증 필터·로그인·refresh 가 이 컬럼만 |

**새 대상을 더할 때도 같은 모양을 지킬 것** — 대상 도메인이 `ContentReportJpaRepo` 를 읽기 시작하면
그 순간 순환이 생긴다.

## 대상을 하나 더할 때 고칠 곳 — 셋을 함께

`ReportTargetType` 에 값만 넣고 끝내지 말 것. `ContentReportService` 의 **세 분기**가 짝이다.

1. `requireTargetAuthor` — 대상 존재 확인 + 작성자 id. 빠지면 접수가 안 된다.
2. `hideTarget` — **조치는 실제로 숨겨야 한다.** 빠지면 "조치했다고 표시됐는데 콘텐츠가 살아 있는"
   가장 나쁜 어긋남이 생긴다(이 레포의 불변식).
3. `previewOf` + `targetAuthorNickNameOf` — 어드민이 대상을 열어보지 않고 판단할 수 있어야 한다.
   ⚠️ 이 둘은 **던지지 않는다** — 큐는 이미 접수된 행을 훑는 화면이라 대상이 사라졌다고 목록 전체가
   500 이 되면 안 된다(접수 경로와 반대다).

`target_type` 은 `varchar(16)` 이라 값을 늘리는 데 **마이그레이션이 필요 없다**. 대신 조치가 쓸 컬럼이
대상 도메인에 필요하면 그건 마이그레이션이다.

## 이 도메인에서 자주 틀리는 것

1. **🔴 대상 접근 권한을 안 보는 것.** 채팅 메시지는 `chatMessageService.requireReportableSender` 로
   **방 접근 권한을 채팅 도메인이 직접** 판정한다. 안 보면 메시지 id 를 올려가며 남의 방을 신고할 수 있고,
   어드민 큐의 **본문 미리보기가 남의 대화를 읽는 채널**이 된다(IDOR). 새 대상이 "일부에게만 보이는 것"
   이면 같은 가드가 필요하다.
2. **다른 도메인의 내부를 직접 만지지 말 것.** 조치는 그 도메인이 노출한 seam 을 쓴다 —
   댓글은 `CommunityCommentService.deleteByModerator`(유저 삭제와 **같은 규칙**을 타야 한다),
   채팅은 `ChatMessageService.deleteByModerator`. 여기서 규칙을 다시 쓰면 두 곳이 갈린다.
3. **중복 신고는 200 멱등**(UNIQUE 가 근거), **자기 것 신고는 400**, **없는 대상도 400**(존재 숨김).
   기대되는 결과는 4xx 가 아니다 — 다만 "신고할 수 없는 것" 은 진짜 거절이다.
4. **UNIQUE 는 `(target_type, target_id, reporter)` 다.** 같은 사람이 같은 대상을 두 번 신고할 수 없다 —
   테스트 데이터를 만들 때 자주 걸린다(다른 신고자를 써야 한다).
5. **삽입은 `global/persistence/IdempotentInsert`.** 제약 위반을 같은 트랜잭션에서 잡으면
   rollback-only 오염으로 결국 500 이다.
6. **경로가 둘인 건 한시적이다.** 정식은 `/reports`·`/admin/reports`, `/community/reports`·
   `/admin/community/reports` 는 별칭이다. FE 셋이 옮기면 지운다.
7. **어드민 큐는 링크를 만들지 않는다.** `targetType` + `targetId` 를 주면 어드민 FE 가 조립한다
   (알림 딥링크와 같은 규칙 — BE 는 URL 을 만들지 않는다).
8. **자동 숨김 임계값은 없다.** 조직적 신고로 정상 글이 사라지는 위험이 어드민 부재 시간대의 노출보다
   크다는 판단이다. 되살리려면 `docs/features/moderation.md` 의 결정을 먼저 뒤집을 것.

## 조치의 효과 — 대상별로 다르다

| 대상 | ACTIONED 하면 | 되돌리기 |
|---|---|---|
| POST | `is_hidden=true` + `moderated_at` | 작성자는 불가. 어드민이 기각(DISMISSED) |
| COMMENT | 유저 삭제와 같은 규칙(대댓글 있으면 자리 남김) | — |
| COURSE | `blocked_at` — 둘러보기·상세·강의 수·연결 카드에서 빠지고 **신규 신청만** 막힌다 | 강사는 불가 |
| CHAT_MESSAGE | 툼스톤(`deleted=true`) — 자리는 남고 본문만 가려진다 | — |
| USER | `suspended_at` — 로그인·refresh 차단 + **살아 있던 토큰도 다음 요청에서** 무효 | `PATCH /admin/accounts/{nickName}/suspension` |

🔴 **USER 조치(정지)는 콘텐츠를 지우지 않는다.** 개별 콘텐츠는 개별 신고로 조치하는 게 이 도메인의
규칙이고, 정지가 글을 쓸어버리면 남의 스레드가 함께 끊긴다. 그리고 **`is_deleted`(탈퇴)에 얹지 말 것** —
익명화 배치가 `isDeleted` 로 대상을 골라서, 합치면 정지 계정의 PII 까지 파기된다.

🔴 **COURSE 조치는 거래를 끊지 않는다.** 이미 확정·결제된 수강·일정·환불 계산은 그대로다.
`enrollment.getCourse()` 를 타는 경로가 많아서(수강 카드·환불 비율·채팅방 제목) 연관관계를 끊는 방식으로
구현하면 조용히 무너진다 — 필터는 **조회 쿼리에만** 더한다.
