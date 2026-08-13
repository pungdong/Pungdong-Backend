package com.diving.pungdong.global.advice;

import com.diving.pungdong.global.advice.exception.*;
import com.diving.pungdong.global.model.CommonResult;
import com.diving.pungdong.global.ResponseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class ExceptionAdvice {

    private final ResponseService responseService;

    private final MessageSource messageSource;

    @ExceptionHandler(ForbiddenTokenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    protected CommonResult invalidToken(ForbiddenTokenException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("forbiddenToken.code")), getMessage("forbiddenToken.msg"));
    }

    @ExceptionHandler(ExpiredRefreshTokenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    protected CommonResult expiredRefreshToken(ExpiredRefreshTokenException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("expiredRefreshToken.code")), getMessage("expiredRefreshToken.msg"));
    }

    @ExceptionHandler(SignInInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    protected CommonResult signInInputException(SignInInputException e){
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

    @ExceptionHandler(CAuthenticationEntryPointException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public CommonResult authenticationEntryPointException(HttpServletRequest request, CAuthenticationEntryPointException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("entryPointException.code")), getMessage("entryPointException.msg"));
    }

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

    @ExceptionHandler(ReservationFullException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult reservationFull(ReservationFullException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("reservationFull.code")), getMessage("reservationFull.msg"));
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

    @ExceptionHandler(EmailDuplicationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public CommonResult emailDuplication(EmailDuplicationException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("emailDuplication.code")), getMessage("emailDuplication.msg"));
    }

    @ExceptionHandler(ClosedLectureException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public CommonResult closedLectureException(ClosedLectureException e) {
        return responseService.getFailResult(Integer.parseInt(getMessage("closedLecture.code")), getMessage("closedLecture.msg"));
    }

    private String getMessage(String code) {
        return getMessage(code, null);
    }

    private String getMessage(String code, Object[] args) {
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }
}
