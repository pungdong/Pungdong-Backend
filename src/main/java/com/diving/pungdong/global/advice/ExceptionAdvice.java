package com.diving.pungdong.global.advice;

import com.diving.pungdong.global.advice.exception.*;
import com.diving.pungdong.global.model.CommonResult;
import com.diving.pungdong.global.model.RateLimitedResult;
import com.diving.pungdong.global.ResponseService;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class ExceptionAdvice {

    private final ResponseService responseService;

    private final MessageSource messageSource;

    @ExceptionHandler(ExpiredRefreshTokenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    protected CommonResult expiredRefreshToken(ExpiredRefreshTokenException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("expiredRefreshToken.code")), getMessage("expiredRefreshToken.msg"));
    }

    @ExceptionHandler(SignInInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    protected CommonResult signInInputException(SignInInputException e){
        // 형식 오류는 "어느 필드가 왜 틀렸는지"를 그대로 돌려준다(badRequest 와 동일 규칙) — 형식 규칙은
        // 이미 공개 계약(types.ts·FE)이라 숨겨서 얻는 보안이 없고, 숨기면 사용자가 고칠 수가 없다.
        // 메시지를 싣지 않은 호출부는 종전대로 일반 문구로 폴백한다.
        if (e.getMessage() != null) {
            return responseService.getFailResult(Integer.parseInt(getMessage("signInInputException.code")), e.getMessage());
        }
        return responseService.getFailResult(Integer.parseInt(getMessage("signInInputException.code")), getMessage("signInInputException.msg"));
    }

    @ExceptionHandler(CUserNotFoundException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    protected CommonResult userNotFound(HttpServletRequest request, CUserNotFoundException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("userNotFound.code")), getMessage("userNotFound.msg"));
    }

    @ExceptionHandler(CEmailSigninFailedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    protected CommonResult emailSigninFailed(HttpServletRequest request, CEmailSigninFailedException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("emailSigninFailed.code")), getMessage("emailSigninFailed.msg"));
    }

    // 참고: entryPointException(-1002) 은 이 advice 가 아니라 CustomAuthenticationEntryPoint 가
    // 직접 401 JSON 으로 발행한다. 아무도 던지지 않던 CAuthenticationEntryPointException 과 그 핸들러는
    // 제거했지만 코드/i18n 키는 그대로 살아 있다.

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public CommonResult accessDeniedException(HttpServletRequest request, AccessDeniedException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("accessDenied.code")), getMessage("accessDenied.msg"));
    }

    @ExceptionHandler(NoPermissionsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public CommonResult noPermissions(HttpServletRequest request, NoPermissionsException e) {
        if (e.getMessage() != null) {
            return responseService.getFailResult(Integer.parseInt(getMessage("noPermissions.code")), e.getMessage());
        }
        return responseService.getFailResult(Integer.parseInt(getMessage("noPermissions.code")), getMessage("noPermissions.msg"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult resourceNotFound(ResourceNotFoundException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("resourceNotFound.code")), getMessage("resourceNotFound.msg"));
    }

    @ExceptionHandler(CoverageHasSessionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult coverageHasSession(CoverageHasSessionException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("coverageHasSession.code")), getMessage("coverageHasSession.msg"));
    }

    @ExceptionHandler(SessionTimeOverlapException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult sessionTimeOverlap(SessionTimeOverlapException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("sessionTimeOverlap.code")), getMessage("sessionTimeOverlap.msg"));
    }

    @ExceptionHandler(PreLaunchException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public CommonResult preLaunch(PreLaunchException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("preLaunch.code")), getMessage("preLaunch.msg"));
    }

    @ExceptionHandler(IdentityVerificationRequiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public CommonResult identityVerificationRequired(IdentityVerificationRequiredException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("identityVerificationRequired.code")), getMessage("identityVerificationRequired.msg"));
    }

    /**
     * 더 비싼 슬롯으로 옮기려 함 — 400 이되 <b>식별 가능한 코드(-1018)</b>. reschedule 의 나머지 실패
     * (만석·확정 회차·슬롯 무효 …)는 그대로 -1011 이라, FE 가 이 코드로만 "차액 결제" 로 분기한다.
     */
    @ExceptionHandler(AdditionalPaymentRequiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult additionalPaymentRequired(AdditionalPaymentRequiredException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("additionalPaymentRequired.code")), getMessage("additionalPaymentRequired.msg"));
    }

    /**
     * 위치까지 바꾸면서 금액이 오르는 변경 — 차액 결제로 <b>갈 수 없는</b> 조합이라 -1018 과 갈라 내보낸다.
     * FE 가 이 코드에는 차액 결제 버튼을 띄우면 안 된다(띄우면 결제 후 엉뚱한 위치로 옮겨진다).
     */
    /**
     * 강사 제안이 만료돼 고를 수 없음 — 사용자 잘못이 아니고 회복 동선이 명확해(직접 일정 선택) 범용 -1011 과 가른다.
     */
    @ExceptionHandler(ProposalExpiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult proposalExpired(ProposalExpiredException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("proposalExpired.code")), getMessage("proposalExpired.msg"));
    }

    @ExceptionHandler(VenueChangeRequiresReapplyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult venueChangeRequiresReapply(VenueChangeRequiresReapplyException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("venueChangeRequiresReapply.code")), getMessage("venueChangeRequiresReapply.msg"));
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult badRequest(BadRequestException e) {
        if (e.getMessage() != null) {
            return responseService.getFailResult(Integer.parseInt(getMessage("badRequest.code")), e.getMessage());
        }
        return responseService.getFailResult(Integer.parseInt(getMessage("badRequest.code")), getMessage("badRequest.msg"));
    }

    /**
     * 요청 body 를 <b>역직렬화하는 단계</b>에서 터진 형식 오류 — {@code @Valid} 보다 앞이라 컨트롤러의
     * {@code BindingResult} 경로를 타지 못한다. 핸들러가 없으면 Spring 기본 {@code /error} 로 빠져
     * {@code {timestamp,status,error,path}} 가 나가고, FE 는 {@code success/code/msg} 가 없어 "알 수 없는 오류"
     * 로만 보여줄 수 있었다(예: {@code endTime: "24:00"} — {@code LocalTime} 은 24:00 을 표현 못 한다).
     *
     * <p>같은 {@code -1011} 이되 msg 에 <b>어느 필드</b>가 틀렸는지 싣는다(형식 규칙은 공개 계약이라 숨길 secret
     * 이 없다 — 루트 CLAUDE.md "Validate input shape"). 값 자체는 에코하지 않는다(길이·PII 무관하게 일관).
     * JSON 자체가 깨져 필드를 특정 못 하면 일반 {@code badRequest.msg}.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult unreadableRequest(HttpMessageNotReadableException e, HttpServletRequest request) {
        String field = fieldPathOf(e.getCause());
        log.info("[request] 역직렬화 실패 {} {} field={}", request.getMethod(), request.getRequestURI(), field);
        return invalidFieldFormat(field);
    }

    /** 쿼리/경로 파라미터 타입 변환 실패(예: {@code ?from=2030-13-99}) — body 형식 오류와 같은 envelope. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult argumentTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        log.info("[request] 파라미터 변환 실패 {} {} field={}", request.getMethod(), request.getRequestURI(), e.getName());
        return invalidFieldFormat(e.getName());
    }

    private CommonResult invalidFieldFormat(String field) {
        int code = Integer.parseInt(getMessage("badRequest.code"));
        if (field == null || field.isEmpty()) {
            return responseService.getFailResult(code, getMessage("badRequest.msg"));
        }
        return responseService.getFailResult(code, getMessage("invalidFieldFormat.msg", new Object[]{field}));
    }

    /** Jackson 매핑 예외의 경로를 {@code a.b[2].c} 꼴로. 매핑 예외가 아니면(JSON 자체 깨짐 등) null. */
    private static String fieldPathOf(Throwable cause) {
        if (!(cause instanceof JsonMappingException)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : ((JsonMappingException) cause).getPath()) {
            if (ref.getFieldName() != null) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * PG 취소 거절 — 응답은 <b>일반 400 문구로 고정</b>한다. 진단 정보(PG resultCode/resultMsg)는 예외가 실어
     * 나르지만 그건 환불 이력({@code RefundOrder})과 로그에만 남기고 클라이언트엔 노출하지 않는다.
     */
    @ExceptionHandler(PaymentGatewayException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult paymentGatewayRejected(PaymentGatewayException e) {
        log.warn("[payment] PG 취소 거절 code={} detail={}", e.getCode(), e.getDetail());
        return responseService.getFailResult(Integer.parseInt(getMessage("badRequest.code")), getMessage("badRequest.msg"));
    }

    /**
     * 낙관적 락 충돌 — 같은 회차/주문을 동시에 바꾸려다 충돌했다. blind overwrite(lost update) 대신 진 쪽
     * 트랜잭션이 롤백됐으니, 클라이언트는 잠시 후 재시도하면 된다(전이는 멱등하거나 상태가 이미 바뀌었다).
     *
     * <p>⚠️ 결제 승인(confirm)이 진 경우 PG 는 이미 청구됐을 수 있다(고아 결제) — orderId/round id 로 대사
     * 가능하게 로그를 남긴다. (승인 원장·PG 호출 트랜잭션 분리는 후속 — 여기선 최소한 추적을 보장.)
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CommonResult concurrentModification(ObjectOptimisticLockingFailureException e) {
        log.warn("[concurrency] 낙관적 락 충돌 — 요청 롤백. entity={} id={}",
                e.getPersistentClassName(), e.getIdentifier(), e);
        return responseService.getFailResult(
                Integer.parseInt(getMessage("concurrentModification.code")), getMessage("concurrentModification.msg"));
    }

    /**
     * 동시 요청이 유니크 제약에 걸려 진 경우(예: 같은 회차 동시 prepare) — 낙관적 락 충돌과 같은 성격이라
     * 동일하게 409 / -1021 로 내려 "잠시 후 재시도" 를 안내한다. 재시도하면 먼저 만들어진 자원을 재사용한다.
     */
    @ExceptionHandler(ConcurrentRequestException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CommonResult concurrentRequest(ConcurrentRequestException e) {
        log.warn("[concurrency] 동시 요청 유니크 충돌 — 재시도로 해결. cause={}",
                e.getCause() == null ? null : e.getCause().getMessage());
        return responseService.getFailResult(
                Integer.parseInt(getMessage("concurrentModification.code")), getMessage("concurrentModification.msg"));
    }

    /**
     * 환불을 지금 처리할 수 없어 상태 전이(거절·취소·만료)를 확정하지 못함(C2) — 앞선 결과 미확인 환불 시도가
     * 대사되면 재시도로 흐른다. 발행자 트랜잭션은 이미 롤백됐고, 사용자에겐 잠시 후 재시도/문의를 안내한다.
     */
    @ExceptionHandler(RefundBlockedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public CommonResult refundBlocked(RefundBlockedException e) {
        log.error("[payment] 환불 처리 불가로 상태 전이 롤백 — {}", e.getMessage());
        return responseService.getFailResult(
                Integer.parseInt(getMessage("refundBlocked.code")), getMessage("refundBlocked.msg"));
    }

    /**
     * 요청이 너무 잦음 — 429. body 에 {@code retryAfterSeconds} 를 더해 "N초 후 다시 시도" 를 그릴 수 있게 한다
     * (절대시각이 아니라 잔여 초인 이유는 {@code RateLimitedResult} 참고).
     *
     * <p>200 + 필드로 주지 않는 이유: 요청이 처리되지 않았는데 200 이면 FE 가 성공으로 오해한다.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public CommonResult tooManyRequests(TooManyRequestsException e) {
        RateLimitedResult result = new RateLimitedResult();
        result.setSuccess(false);
        result.setCode(Integer.parseInt(getMessage("tooManyRequests.code")));
        result.setMsg(getMessage("tooManyRequests.msg"));
        result.setRetryAfterSeconds(e.getRetryAfterSeconds());
        return result;
    }

    @ExceptionHandler(EmailDuplicationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult emailDuplication(EmailDuplicationException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("emailDuplication.code")), getMessage("emailDuplication.msg"));
    }

    private String getMessage(String code) {
        return getMessage(code, null);
    }

    private String getMessage(String code, Object[] args) {
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }
}
