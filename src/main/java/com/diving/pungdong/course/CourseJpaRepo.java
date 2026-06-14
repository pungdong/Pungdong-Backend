package com.diving.pungdong.course;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseJpaRepo extends JpaRepository<Course, Long> {
    List<Course> findAllByInstructorIdOrderByIdDesc(Long instructorId);
}
