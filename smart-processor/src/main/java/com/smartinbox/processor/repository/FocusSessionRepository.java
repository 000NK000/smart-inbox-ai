package com.smartinbox.processor.repository;

import com.smartinbox.processor.entity.FocusSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface FocusSessionRepository extends JpaRepository<FocusSession, String> {
    Optional<FocusSession> findFirstByEndedAtIsNullOrderByStartedAtDesc();
    @Query("select s from FocusSession s where s.startedAt < :end and (s.endedAt is null or s.endedAt > :start)")
    List<FocusSession> overlapping(@Param("start") long start, @Param("end") long end);
}
