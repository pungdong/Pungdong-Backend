package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.dto.CapacityRequest;
import com.diving.pungdong.availability.dto.CoverageRequest;
import com.diving.pungdong.availability.dto.HoldRequest;
import com.diving.pungdong.availability.dto.SessionCreateRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;

/**
 * 강사 캘린더 — coverage(예약가능시간) + session(일정) 두 레이어. 매처 {@code /instructor/availability/**}
 * → authenticated({@code global/security/SecurityConfiguration}). 게이트(강사신청 보유)는 서비스에서.
 *
 * <ul>
 *   <li>coverage: POST {@code /coverage}(열기) · DELETE {@code /coverage}(닫기, 일정 걸치면 -1014).</li>
 *   <li>session: POST {@code /sessions}(원자 추가) · {id} 디테일/삭제 · {id}/capacity(override) · {id}/holds.</li>
 *   <li>범위 조회 GET {@code ?from&to} → {coverage[], sessions[]} 분리. 정원 baseline GET/PATCH {@code /settings}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/instructor/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    /* ─── 정원 baseline ─── */

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@CurrentUser Account account) {
        return ResponseEntity.ok(availabilityService.getSettings(account));
    }

    @PatchMapping("/settings")
    public ResponseEntity<?> updateSettings(@CurrentUser Account account, @RequestBody CapacityRequest request) {
        return ResponseEntity.ok(availabilityService.updateDefaultCapacity(account, request.getCapacity()));
    }

    /* ─── coverage(예약가능시간) ─── */

    @PostMapping("/coverage")
    public ResponseEntity<?> openCoverage(@CurrentUser Account account,
                                          @Valid @RequestBody CoverageRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok(availabilityService.openCoverage(account, request));
    }

    @DeleteMapping("/coverage")
    public ResponseEntity<?> closeCoverage(@CurrentUser Account account,
                                           @Valid @RequestBody CoverageRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok(availabilityService.closeCoverage(account, request));
    }

    /* ─── 범위 조회 ─── */

    @GetMapping
    public ResponseEntity<?> calendar(@CurrentUser Account account,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(availabilityService.getCalendar(account, from, to));
    }

    /* ─── session(일정) ─── */

    /** 일정 원자 추가 — coverage 확장+머지 후 (위치,시간) 일정 생성/join + 점유. 201. */
    @PostMapping("/sessions")
    public ResponseEntity<?> addSession(@CurrentUser Account account,
                                        @Valid @RequestBody SessionCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.status(201).body(availabilityService.addSession(account, request));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> session(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok(availabilityService.getSession(account, id));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@CurrentUser Account account, @PathVariable Long id) {
        availabilityService.deleteSession(account, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/sessions/{id}/capacity")
    public ResponseEntity<?> setCapacity(@CurrentUser Account account, @PathVariable Long id,
                                         @RequestBody CapacityRequest request) {
        return ResponseEntity.ok(availabilityService.setSessionCapacity(account, id, request.getCapacity()));
    }

    @DeleteMapping("/sessions/{id}/capacity")
    public ResponseEntity<?> resetCapacity(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok(availabilityService.resetSessionCapacity(account, id));
    }

    @PostMapping("/sessions/{id}/holds")
    public ResponseEntity<?> addHold(@CurrentUser Account account, @PathVariable Long id,
                                     @RequestBody HoldRequest request) {
        return ResponseEntity.status(201).body(availabilityService.addHold(account, id, request));
    }

    @DeleteMapping("/sessions/{id}/holds/{holdId}")
    public ResponseEntity<?> removeHold(@CurrentUser Account account, @PathVariable Long id,
                                        @PathVariable Long holdId) {
        return ResponseEntity.ok(availabilityService.removeHold(account, id, holdId));
    }
}
