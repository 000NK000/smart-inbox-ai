package com.smartinbox.processor.repository;
import com.smartinbox.processor.entity.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface JobApplicationRepository extends JpaRepository<JobApplication,String>{
    List<JobApplication> findAllByOrderByUpdatedAtDesc();
}
