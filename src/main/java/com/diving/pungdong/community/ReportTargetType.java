package com.diving.pungdong.community;

/** 신고 대상 종류. 게시물과 댓글 두 가지라 폴리모픽 참조를 {@code (targetType, targetId)} 로 표현한다. */
public enum ReportTargetType {
    POST,
    COMMENT
}
