package com.smartinbox.processor.repository;

import com.smartinbox.processor.entity.WatchListEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WatchListRepository extends JpaRepository<WatchListEntry, String> {
    List<WatchListEntry> findAllByOrderByUpdatedAtDesc();
    Optional<WatchListEntry> findByKindAndTitleKey(String kind, String titleKey);
}
