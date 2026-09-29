package com.smartinbox.processor.repository;

import com.smartinbox.processor.entity.TaskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;

@Repository
public interface TaskItemRepository extends JpaRepository<TaskItem, String> {
    List<TaskItem> findAllByOrderByCreatedAtDesc();
    Optional<TaskItem> findBySourceMailIdAndSourceSuggestionId(Long mailId, String suggestionId);
    @Query("select t.id as id, t.version as version, t.updatedAt as updatedAt, t.status as status, t.dueAt as dueAt from TaskItem t order by t.id")
    List<TaskCounterRow> findCounterRows();
    interface TaskCounterRow {
        String getId(); Long getVersion(); Long getUpdatedAt(); String getStatus(); Long getDueAt();
    }
}
