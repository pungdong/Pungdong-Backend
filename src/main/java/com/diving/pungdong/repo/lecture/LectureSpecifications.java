package com.diving.pungdong.repo.lecture;

import com.diving.pungdong.domain.lecture.Lecture;
import com.diving.pungdong.domain.lecture.Organization;
import com.diving.pungdong.dto.lecture.list.search.CostCondition;
import com.diving.pungdong.dto.lecture.list.search.FilterSearchCondition;
import org.springframework.data.jpa.domain.Specification;

public final class LectureSpecifications {

    private LectureSpecifications() {
    }

    public static Specification<Lecture> matching(FilterSearchCondition condition) {
        return Specification.where(organizationEq(condition.getOrganization()))
                .and(classKindEq(condition.getClassKind()))
                .and(levelEq(condition.getLevel()))
                .and(regionEq(condition.getRegion()))
                .and(costBetween(condition.getCostCondition()));
    }

    private static Specification<Lecture> organizationEq(Organization organization) {
        return organization == null ? null
                : (root, query, cb) -> cb.equal(root.get("organization"), organization);
    }

    private static Specification<Lecture> classKindEq(String classKind) {
        return classKind == null ? null
                : (root, query, cb) -> cb.equal(root.get("classKind"), classKind);
    }

    private static Specification<Lecture> levelEq(String level) {
        return level == null ? null
                : (root, query, cb) -> cb.equal(root.get("level"), level);
    }

    private static Specification<Lecture> regionEq(String region) {
        return region == null ? null
                : (root, query, cb) -> cb.equal(root.get("region"), region);
    }

    private static Specification<Lecture> costBetween(CostCondition costCondition) {
        if (costCondition == null || costCondition.equals(new CostCondition())) {
            return null;
        }
        return (root, query, cb) -> cb.between(root.get("price"),
                costCondition.getMin(), costCondition.getMax());
    }
}
