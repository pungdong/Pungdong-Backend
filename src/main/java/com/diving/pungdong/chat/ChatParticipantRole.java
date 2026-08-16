package com.diving.pungdong.chat;

/**
 * 방 안에서의 역할 — <b>전역 {@code Role} 이 아니라 이 방 기준</b>이다. 강사도 남의 수업에선 학생이다.
 *
 * <p>표시명 합성에 쓰인다: {@code INSTRUCTOR} → "김민지 강사", {@code STUDENT} → "김수민 학생".
 * 합성은 <b>BE 가 한다</b>({@code displayName}) — FE 가 하면 web/mobile 사본 2벌이 어긋난다.
 */
public enum ChatParticipantRole {

    INSTRUCTOR("강사"),
    STUDENT("학생");

    private final String label;

    ChatParticipantRole(String label) {
        this.label = label;
    }

    /** 표시명 접미사. */
    public String label() {
        return label;
    }

    /** "{닉네임} {강사|학생}". 닉네임이 없으면 접미사만 남기지 않고 null 을 돌려준다(빈 라벨 방지). */
    public String displayName(String nickName) {
        if (nickName == null || nickName.isBlank()) {
            return null;
        }
        return nickName + " " + label;
    }
}
