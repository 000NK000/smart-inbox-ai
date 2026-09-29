package com.smartinbox.processor.repository;

import com.smartinbox.processor.entity.PracticeSolutionImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PracticeSolutionImageRepository extends JpaRepository<PracticeSolutionImage, String> {
    List<PracticeSolutionImage> findByProblemNumberOrderByPositionAsc(Integer number);
}
