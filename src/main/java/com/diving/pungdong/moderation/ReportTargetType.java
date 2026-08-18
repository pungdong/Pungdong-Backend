package com.diving.pungdong.moderation;

/**
 * 신고 대상 종류. 폴리모픽 참조를 {@code (targetType, targetId)} 쌍으로 표현한다 — 종류가 여럿이라
 * DB FK 를 걸 수 없고, 대상 존재 확인은 접수 시점에 서비스가 한다.
 *
 * <p><b>한 테이블·한 큐를 유지한다.</b> 대상별로 테이블을 나누면 어드민이 화면을 여러 개 봐야 하고
 * "미처리 몇 건" 이 합산되지 않는다. 늘어나는 건 값 하나이고 {@code target_type} 은 varchar(16) 이라
 * 컬럼 마이그레이션도 필요 없다.
 *
 * <p>대상을 더할 때 <b>세 곳을 함께</b> 고쳐야 한다({@code ContentReportService}):
 * 작성자 해석({@code requireTargetAuthor}) · 조치({@code hideTarget}) · 미리보기({@code previewOf}).
 * 하나라도 빠지면 접수는 되는데 어드민이 열 수 없거나, 조치했다는데 콘텐츠가 살아 있는 상태가 된다.
 */
public enum ReportTargetType {

    /** 커뮤니티 게시물(= 브랜딩 게시물, 같은 행). 조치 → 숨김 + 작성자 복구 불가. */
    POST,

    /** 커뮤니티 댓글. 조치 → 유저 삭제와 같은 규칙(대댓글 있으면 자리 남김). */
    COMMENT,

    /**
     * 강의(코스). 조치 → 어드민 전용 차단 플래그로 둘러보기·상세·신규 신청에서 제외.
     * <b>이미 확정·결제된 수강은 건드리지 않는다</b>(레포의 "확정 취소 없음" 원칙).
     */
    COURSE,

    /** 단체 채팅 메시지. 조치 → 툼스톤. 신고자는 <b>그 방에 접근 가능해야</b> 한다(IDOR 방지). */
    CHAT_MESSAGE
}
