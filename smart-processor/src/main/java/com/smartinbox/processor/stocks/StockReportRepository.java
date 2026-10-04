package com.smartinbox.processor.stocks;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface StockReportRepository extends JpaRepository<StockReport,String> {
    List<StockReport> findTop50ByOrderByCreatedAtDesc();
}
