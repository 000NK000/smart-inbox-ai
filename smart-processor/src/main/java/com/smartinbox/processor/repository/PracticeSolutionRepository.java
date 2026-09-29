package com.smartinbox.processor.repository;

import com.smartinbox.processor.entity.PracticeSolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface PracticeSolutionRepository extends JpaRepository<PracticeSolution, Integer> {
    @Query("select s.number from PracticeSolution s order by s.number")
    List<Integer> findAllNumbers();
}
