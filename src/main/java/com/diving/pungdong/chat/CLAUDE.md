# CLAUDE.md — chat (세션 단체 채팅 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. `availability`(repo) · `enrollment`(repo) · `account`(repo) · `venue`(VenueRefResolver) 를 **읽기 전용 단방향 참조**.

## 무엇이 들어있나 — 일정(session) 단위 단체 채팅

강사 가용시간 슬롯 하나 = 채팅방 하나. 강사 "내 일정 > 슬롯 상세" 의 **세션 단체 채팅** CTA, 그리고 수강관리·강의일정 **회차 카드의 채팅 버튼**으로 들어간다. 채팅 목록 화면은 **없다**(사용자 결정 D2).

- **컨트롤러**: `ChatController`(`/chat/**`) — 방 상세(없으면 생성) · 참여자 · 메시지 커서 목록 · 전송 · 읽음.
- **서비스**: `ChatRoomService`(해석·지연 생성·참여자 reconcile·표시명) + `ChatMessageService`(커서 목록·멱등 전송·레이트리밋·읽음) + `ChatQueryService`(회차 카드용 **일괄** 상태 조회).
- **엔티티**: `ChatRoom`(PK=세션 id) → `ChatParticipant` · `ChatMessage` · `ChatReadState`.
- **enum**: `ChatRoomState`(HIDDEN/ACTIVE/CLOSED — 파생) · `ChatParticipantRole`(INSTRUCTOR/STUDENT — **방 기준**) · `ChatMessageKind`(USER/SYSTEM).
- **유틸**: `ChatRooms`(civil→instant 마감 계산, KST 고정).

보안 매처(`/chat/**` → authenticated)는 `global/security/SecurityConfiguration`. 역할로 가르지 않는 이유는 강사·수강생이 **같은 방**을 쓰기 때문 — 실제 판정(그 방의 참여자인가)은 서비스가 방마다 한다.

## 🔴 이 도메인의 핵심 불변식 3개

### 1. 방 PK = 세션 id, `availability_session` 으로의 FK 없음

`availability.SessionCleaner` 는 **점유가 0 이 되면 세션 행을 물리 삭제**한다(결제자 전원 취소·환불 시 실제로 일어남). FK 가 있으면 그 삭제가 제약 위반으로 실패해 **환불 플로우가 깨진다.** FK 를 없앤 덕에:
- `SessionCleaner` **무변경**(채팅 때문에 손댈 곳이 없다)
- 세션이 사라져도 방·메시지가 남아 **옛 푸시 딥링크가 계속 동작**한다(CLOSED 읽기 전용)
- 방 행이 없어도 `roomId` 를 알 수 있어 **목록 조회가 방을 만들지 않는다**

**안전성 근거**: prod RDS·로컬 모두 **MySQL 8.4**. 8.0+ 는 AUTO_INCREMENT 카운터를 영속하고 삭제된 id 를 재사용하지 않으므로, 한 번 쓰인 세션 id 가 다른 세션에 다시 붙지 않는다 → 옛 CLOSED 방과 PK 충돌이 없다.

⚠️ **관례 이탈 1 (리드 승인)**: 이 레포는 PK 가 예외 없이 `@GeneratedValue(IDENTITY)` 인데 `ChatRoom` 만 assigned PK 다. 그래서 `Persistable<Long>` 을 구현해 `isNew()` 를 명시한다 — 안 하면 Spring Data 가 id 유무로 판단해 신규 저장이 `merge` 로 나간다.

### 2. 참여 자격 = `EnrollmentStatus.OCCUPYING` = {ACCEPT_PENDING, CONFIRMED}

이 도메인은 **선결제**다 — 결제가 강사 수락보다 먼저 일어나고 **미결제 상태는 `PENDING` 하나뿐**이다. `ACCEPT_PENDING` 은 "결제완료·강사 결정 대기" 이므로 **포함**된다(`RoundScheduleStatus.from` 이 `PENDING → PAYMENT_DUE`, `ACCEPT_PENDING → WAITING` 으로 매핑하는 게 근거). 디자인의 "결제 전 = 미생성" 에서 빠지는 건 `PENDING` 뿐이다.

### 3. 참여자 목록과 발신자 이름 해석은 **다른 쿼리**를 쓴다

- `participants[]` / `participantCount` → **현재 참여자만**(`leftAt IS NULL`). 헤더 "참여자 3명" 이 맞아야 한다.
- 메시지의 발신자 이름 매핑 → **이탈자 행도 포함**(`findByRoomId`). 빼면 환불로 나간 사람이 과거에 남긴 말풍선의 이름이 **빈칸**이 된다.

같은 테이블을 보는 두 용도라 쿼리가 갈린다. 한쪽으로 통일하려는 리팩터링은 둘 중 하나를 깬다.

## 그 외 결정과 함정

- **지연 생성 (lazy)** — 자격 있는 사용자가 방을 **처음 열 때** 만든다. `PaymentCompletedEvent` 를 듣는 안은 검토 후 **기각**했다: `PaymentService.publishPaymentCompleted` javadoc 이 *"알림 리스너는 결제 트랜잭션에 합류하므로 여기서 실패하면 **승인된 결제가 롤백**된다"* 고 경고한다. 리스너는 `@Transactional(MANDATORY)` 라 방 생성이 던지면 승인된 결제가 날아간다.
- ⚠️ **관례 이탈 2 (리드 승인) — 커서 페이지네이션.** 이 레포의 다른 목록은 전부 `Pageable` + `PagedResourcesAssembler` → `PagedModel` 이다. 채팅만 `before`/`after` 커서 + plain DTO 를 쓴다 — append-heavy 라 새 메시지가 들어오면 offset 페이지가 밀려 과거 스크롤에서 중복·누락이 난다.
  - `before` 는 **커서에 가장 가까운 과거** N건이다(`ORDER BY id DESC` 후 역순 반환). ASC 로 뽑으면 "가장 오래된 N건" 이 나와 위로 스크롤할 때마다 **대화 맨 처음으로 점프**한다.
  - 응답은 방향과 무관하게 **항상 id 오름차순**. 종료조건은 HAL 래핑이 아니라 **명시 필드 `hasMore`**(커뮤니티 댓글에서 `page` 블록이 사라져 무한스크롤이 깨진 이력 때문).
  - **빈 목록이면 `nextCursor` 가 요청 커서를 그대로 에코**한다. null 을 주면 호출부가 `cursor = res.nextCursor` 했을 때 커서가 날아가고, `after` 폴링은 대부분 빈 목록이라 다음 폴링이 최신 N건을 통째로 다시 가져와 **중복 렌더**가 난다.
- **전송 멱등 (`clientMessageId`)** — UNIQUE `(sender_account_id, client_message_id)`. 중복이면 **에러가 아니라 기존 메시지를 200**(신규 201). **중복은 푸시를 재발행하지 않는다** — 재시도 한 번에 참여자 전원이 알림을 두 번 받으면 안 된다.
  - ⚠️ **UUID 포맷을 강제하지 않는다**(`@Pattern` 없음). RN(Hermes)에 WebCrypto 가 없어 강제하면 모바일에 네이티브 의존성이 붙는다. UNIQUE 가 sender 기준이라 사용자 간 충돌은 무의미하다.
- **마감 = (date, endTime)@KST + 24h** — 슬롯 시각은 오프셋 없는 civil 이라 존을 붙여야 절대시각이 된다. `venue.timeZone` 이 아직 없어 KST 고정(`payment.RefundService` 와 같은 선례). 응답엔 **절대시각이 아니라 잔여 초**(`closesInSeconds`) — 기기 시계가 어긋나도 안 밀리게(`paymentExpiresInSeconds` 규칙). 슬롯 시간이 바뀌면 **늘어날 수도** 있다.
- **표시명은 BE 가 합성한다**(`displayName` = "김수민 학생"). FE 합성으로 두면 web/mobile 사본 2벌이 어긋난다(이 레포에서 반복된 부류). `name`/`role`/`accountId` 도 함께 준다.
- **레이트리밋** = 최근 10초 10건(상수는 `ChatMessageService`). 범용 인프라가 레포에 없어(유일 선례는 본인확인 OTP 의 Redis 쿨다운) DB count 로 하는 최소 가드다. 초과 시 **429 + `-1023` + body `retryAfterSeconds`**.
- **unread 는 사람 메시지만 센다** — 개설 안내(`SYSTEM`)는 제외라 **새 방은 0**. 조건을 `kind = USER` 로 **명시**할 것: `senderAccountId <> :me` 만으로도 걸러지는 것처럼 보이나 그건 3치 논리(`NULL <> :me` = UNKNOWN)에 우연히 기대는 형태고, 실제로 계약 문서와 구현이 이 지점에서 어긋났다(FE 가 문서 읽고 잡음).
- **없음/비참여 = `ResourceNotFoundException`** → 이 레포에선 **HTTP 400 + code `-1009`**(404 아님). FE 딥링크 폴백은 status 가 아니라 **code** 로 건다.
- **`CLOSED` 방 조회는 200** 이다(읽기 전용이라 대화는 보여야 한다). 400 은 **전송**만.
- **SYSTEM 문구에 날짜를 넣지 않는다** — `"회차 채팅방이 열렸어요"` 만. 디자인의 `"12/3 (화) · "` 접두와 날짜 구분선은 FE 가 `sentAt` 으로 합성한다.

## 순환 참조 주의

`enrollment`/`availability` 응답 조립부가 `ChatQueryService` 를 주입받는다(회차 카드의 채팅 상태). 그래서 **chat 쪽은 그 도메인들의 레포만 참조**해야 한다 — 서비스끼리 물리면 Spring Boot 2.6+ 가 기본 금지하는 순환 참조로 **부팅이 실패**한다.

## 작업 전 반드시 읽기

- **[docs/features/session-group-chat.md](../../../../../../../docs/features/session-group-chat.md)** — 정책·왜·히스토리. **여기부터.**
- [availability/CLAUDE.md](../availability/CLAUDE.md) — 특히 "session 존재 ⟺ 점유 > 0"(`SessionCleaner`)
- [notification/CLAUDE.md](../notification/CLAUDE.md) — fan-out·outbox·채널
- 컨트롤러 시그니처/응답/enum 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 안전망 테스트

`src/test/.../usecase/ChatUseCaseTest` — 실 H2 + 시큐리티 체인. 그룹:
- **S\*** 방 열기(지연 생성)·개설 안내 메시지·전송·`mine` 시점별 판정·참여자
- **C\*** 커서(인접 과거 / 초기 진입 backward / 빈 폴링 커서 에코 / before+after 400)
- **I\*** 멱등(재전송 200·메시지 1건 / 키 누락 400)
- **R\*** 권한(미결제·무관자 -1009 / 비로그인 401)
- **X\*** 수명(마감 CLOSED 읽기전용 / **세션 물리삭제 후에도 방 생존**)
- **U\*** unread·읽음 전진
- **H\*** 회차 카드의 `sessionId`+`chat` 노출(HIDDEN 은 항상 non-null)

⚠️ `Authorization` 헤더는 **raw JWT**(Bearer 없음).
⚠️ 픽스처의 회차에 `venueRefId` 를 반드시 채울 것 — 학생 hub 는 회차에 venueRefId 가 하나도 없으면 `resolveNames` 가 `Map.of()` 를 돌려주고 `get(null)` 에서 NPE(500)가 난다. **채팅과 무관한 기존 결함**이라 테스트가 피해 간다.

## 아직 안 한 것 (백로그)

- 메시지 신고 — 커뮤니티 `ContentReport`(다형 타겟 + UNIQUE 멱등 + 어드민 큐) 패턴 복사
- 첨부/이미지 — `global/storage/S3Uploader` + `ImageUploadPolicy` 재사용 가능(v1 은 텍스트 전용, `[+]` 숨김)
- per-room 알림 mute — `notification_preference` 신설 필요
- 메시지 삭제 API — `deleted` 컬럼과 툼스톤 문구만 있고 경로는 없다
- 정원이 커지면 푸시 fan-out 재검토(현재 메시지 1건 = 참여자 N명 × outbox+알림함 2행)
