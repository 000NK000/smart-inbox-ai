package com.smartinbox.processor.repository;

import com.smartinbox.processor.entity.CalendarEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, String> {
    @Query("select e from CalendarEvent e where e.boundsStart < :end and e.boundsEnd > :start order by e.boundsStart, e.id")
    List<CalendarEvent> findOverlapping(@Param("start") long start, @Param("end") long end, Pageable limit);
}
