package com.smartinbox.processor.repository;
import com.smartinbox.processor.entity.JobMailLink;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface JobMailLinkRepository extends JpaRepository<JobMailLink,String>{
    List<JobMailLink> findAllByApplicationIdOrderByLinkedAtDesc(String applicationId);
    List<JobMailLink> findAllByMailIdOrderByLinkedAtDesc(Long mailId);
    long countByApplicationId(String applicationId);
    void deleteAllByApplicationId(String applicationId);
}
