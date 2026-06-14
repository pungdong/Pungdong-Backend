package com.diving.pungdong.course;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface CourseJpaRepo extends JpaRepository<Course, Long>, JpaSpecificationExecutor<Course> {
    List<Course> findAllByInstructorIdOrderByIdDesc(Long instructorId);
}
