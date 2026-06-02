package com.diving.pungdong.account.dto.signUp;

import com.diving.pungdong.account.dto.auth.AuthToken;
import lombok.Builder;
import lombok.Data;

/**
 * 회원가입 응답.
 * <p>
 * 가입과 동시에 로그인 처리되므로 {@link AuthToken} 을 함께 내려준다 —
 * 클라이언트는 별도 /sign/login 호출 없이 즉시 인증 상태로 진입 가능.
 */
@Data
@Builder
public class SignUpResult {
    String email;
    String nickName;
    AuthToken tokens;
}
