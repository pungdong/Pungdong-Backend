package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.availability.dto.AvailabilityCreateRequest;
import com.diving.pungdong.availability.dto.AvailabilitySettingsResponse;
import com.diving.pungdong.availability.dto.AvailabilityUpdateRequest;
import com.diving.pungdong.availability.dto.AvailabilityWindowResponse;
import com.diving.pungdong.availability.dto.CapacityRequest;
import com.diving.pungdong.availability.dto.HoldRequest;
import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 강사 가용시간 캘린더 — 가용시간(window) 생성/조회/수정/삭제 + 점유(hold) 추가/제거.
 *
 * <p>매처: {@code /instructor/availability/**} → authenticated ({@code global/security/SecurityConfiguration}).
 * 역할이 아니라 인증으로 둔 이유는 venue 와 동일 — 강사신청 리뷰 대기(SUBMITTED) 중인 사람은 아직 STUDENT
 * 라서. 실제 게이트(강사신청 보유)는 {@link AvailabilityService} 에서 강제.
 *
 * <p>v1 은 BE-소유 코어만 — 풍덩 수강생 신청(pending/confirmed)·applicants 는 enrollment 도메인이 생길 때
 * 채워진다. 상세 docs/features/instructor-availability.md.
 */
@RestController
@RequestMapping(value = "/instructor/availability", produces = MediaTypes.HAL_JSON_VALUE)
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    /** 가용시간 생성 — recurrence(ONCE/WEEKLY/FOUR_WEEKS)를 서버에서 다중 window 로 전개. 201. */
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser Account account,
                                    @Valid @RequestBody AvailabilityCreateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        List<AvailabilityWindowResponse> created = availabilityService.create(account, request);
        return ResponseEntity.status(201).body(collection(created));
    }

    /** 기본 정원(baseline) 읽기 — 일정탭 ± 가 표시할 현재 값. {@code /{id}} 보다 구체적이라 우선 매칭. */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@CurrentUser Account account) {
        return ResponseEntity.ok().body(availabilityService.getSettings(account));
    }

    /** 기본 정원 조정 — 일정탭 상단 ±. override 없는 일정들이 라이브로 따라간다(전파 write 없음). */
    @PatchMapping("/settings")
    public ResponseEntity<?> updateSettings(@CurrentUser Account account, @RequestBody CapacityRequest request) {
        return ResponseEntity.ok().body(availabilityService.updateDefaultCapacity(account, request.getCapacity()));
    }

    /** 캘린더 읽기 — [from, to] 범위(일/주/월 뷰는 FE 가 범위로 표현). */
    @GetMapping
    public ResponseEntity<?> list(@CurrentUser Account account,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().body(collection(availabilityService.list(account, from, to)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok().body(model(availabilityService.getMine(account, id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser Account account, @PathVariable Long id,
                                    @Valid @RequestBody AvailabilityUpdateRequest request, BindingResult result) {
        if (result.hasErrors()) {
            throw new BadRequestException();
        }
        return ResponseEntity.ok().body(model(availabilityService.update(account, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@CurrentUser Account account, @PathVariable Long id) {
        availabilityService.delete(account, id);
        return ResponseEntity.noContent().build();
    }

    /** 그 일정만 정원 고정(override 설정) — 일정 카드 ±. 갱신 window 반환. */
    @PatchMapping("/{id}/capacity")
    public ResponseEntity<?> setCapacity(@CurrentUser Account account, @PathVariable Long id,
                                         @RequestBody CapacityRequest request) {
        return ResponseEntity.ok().body(model(availabilityService.setWindowCapacity(account, id, request.getCapacity())));
    }

    /** 일정 override 해제 — "기본값 따르기". 이후 계정 기본값을 라이브로 따른다. 갱신 window 반환. */
    @DeleteMapping("/{id}/capacity")
    public ResponseEntity<?> resetCapacity(@CurrentUser Account account, @PathVariable Long id) {
        return ResponseEntity.ok().body(model(availabilityService.resetWindowCapacity(account, id)));
    }

    /** 점유 추가 — 외부예약(memo) / ± 빠른조정(memo 없음). 유효정원 초과해도 기록(FULL 표시). 갱신 window 반환. */
    @PostMapping("/{id}/holds")
    public ResponseEntity<?> addHold(@CurrentUser Account account, @PathVariable Long id,
                                     @RequestBody HoldRequest request) {
        return ResponseEntity.status(201).body(model(availabilityService.addHold(account, id, request)));
    }

    @DeleteMapping("/{id}/holds/{holdId}")
    public ResponseEntity<?> removeHold(@CurrentUser Account account, @PathVariable Long id,
                                        @PathVariable Long holdId) {
        return ResponseEntity.ok().body(model(availabilityService.removeHold(account, id, holdId)));
    }

    private EntityModel<AvailabilityWindowResponse> model(AvailabilityWindowResponse response) {
        EntityModel<AvailabilityWindowResponse> model = EntityModel.of(response);
        model.add(Link.of("/docs/api.html#resource-instructor-availability").withRel("profile"));
        return model;
    }

    private CollectionModel<AvailabilityWindowResponse> collection(List<AvailabilityWindowResponse> windows) {
        CollectionModel<AvailabilityWindowResponse> model = CollectionModel.of(windows);
        model.add(Link.of("/docs/api.html#resource-instructor-availability").withRel("profile"));
        return model;
    }
}
