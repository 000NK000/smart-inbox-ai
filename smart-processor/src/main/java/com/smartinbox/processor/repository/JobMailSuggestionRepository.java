package com.smartinbox.processor.repository;
import com.smartinbox.processor.entity.JobMailSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface JobMailSuggestionRepository extends JpaRepository<JobMailSuggestion,Long>{
    List<JobMailSuggestion> findAllByRecruitmentTrueAndStatusOrderByAnalyzedAtDesc(String status);
}
