package com.smartinbox.processor.stocks;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface StockWatchRepository extends JpaRepository<StockWatchEntry,String> {
    List<StockWatchEntry> findAllByOrderByCreatedAtDesc();
    Optional<StockWatchEntry> findBySymbolAndExchange(String symbol,String exchange);
}
