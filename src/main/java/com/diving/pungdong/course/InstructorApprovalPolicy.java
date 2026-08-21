package com.diving.pungdong.course;

import com.diving.pungdong.global.advice.exception.BadRequestException;
import com.diving.pungdong.instructorapplication.InstructorApplicationJpaRepo;
import com.diving.pungdong.instructorapplication.InstructorApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * <b>공개·판매는 정식 강사만</b> — 강의가 학생에게 보이거나 팔리려면 그 강의 종목의 승인(APPROVED) 신청이
 * 있어야 한다.
 *
 * <h3>준비와 판매를 가르는 선 (핵심)</h3>
 * 강사 검수는 <b>수동이라 하루쯤 걸린다.</b> 그래서 신청해 둔 사람이 그동안 <b>강의·일정을 만들어 두는 것은
 * 의도적으로 허용</b>한다 — 앱에 온 김에 할 수 있는 걸 다 해두게 하려는 제품 결정이다.
 * 하지만 <b>승인 전에는 정식 강사가 아니므로 그 강의가 노출되면 안 된다.</b>
 * 이 클래스가 그 선을 긋는다.
 *
 * <table>
 *   <tr><th>구간</th><th>승인 필요</th></tr>
 *   <tr><td>강의 생성·수정, 가용시간·일정 등록</td><td>❌ 신청만 있으면 됨(그대로 열어 둔다)</td></tr>
 *   <tr><td>OPEN 전환(발행)</td><td>✅ {@link #requireApprovedToPublish}</td></tr>
 *   <tr><td>둘러보기·공개 상세·신청/결제</td><td>✅ {@link #isApproved} / {@code CourseSpecifications}</td></tr>
 * </table>
 *
 * <h3>왜 읽기에도 거나 — 쓰기만으로는 안 새는 게 보장되지 않는다</h3>
 * OPEN 전환 때만 막으면 <b>열어 둔 뒤 반려된</b> 강사의 강의가 계속 팔린다(상태는 이미 OPEN 이고
 * 반려는 그 값을 건드리지 않는다). 그래서 조회·신청 경로에도 같은 조건을 건다. 이건
 * {@code blocked_at} 이 밟은 길과 같다 — 코드 주석에 이미 있다: <b>"둘러보기에서만 빼면 상세 URL 이
 * 우회로가 된다."</b>
 *
 * <h3>데모(seeded) 코스도 예외가 아니다</h3>
 * 데모 노출은 {@code SiteSettings.showSeededCourses} 라는 <b>별개 축</b>이 담당한다. 여기에 시드 예외를
 * 넣지 말 것 — 두 가지 이유로 틀린다. (1) 그러면 "노출되려면 승인된 강사" 라는 규칙에 특례가 생겨
 * 읽기 경로마다 조건이 갈린다. (2) 무엇보다 <b>prod 의 데모 코스는 {@code seeded} 표식이 누락된 이력이
 * 있다</b>({@code SEED_SKIP_DB} 로 시드) — 시드 예외는 정작 거기서 안 먹는다.
 * 데모 강사는 <b>어드민에서 실제로 승인</b>해서 규칙을 그대로 통과시킨다.
 */
@Component
@RequiredArgsConstructor
public class InstructorApprovalPolicy {

    private final InstructorApplicationJpaRepo applicationRepo;

    /** 이 강의가 공개·판매될 수 있는가 = 강사가 <b>그 종목</b>의 승인을 가졌는가. */
    public boolean isApproved(Course course) {
        if (course == null || course.getInstructor() == null || course.getDisciplineCode() == null) {
            return false;
        }
        return applicationRepo.existsByAccountIdAndDisciplineCodeAndStatus(
                course.getInstructor().getId(), course.getDisciplineCode(),
                InstructorApplicationStatus.APPROVED);
    }

    /**
     * 발행(OPEN 전환) 게이트. 조회 쪽의 "존재 숨김" 과 달리 <b>오너 본인의 행동</b>이라 그냥 400 을 던진다 —
     * 여기서 조용히 성공시키면 강사는 "분명 OPEN 했는데 왜 아무도 안 들어오지" 를 영영 알 수 없다.
     */
    public void requireApprovedToPublish(Course course) {
        if (!isApproved(course)) {
            throw new BadRequestException();
        }
    }
}
