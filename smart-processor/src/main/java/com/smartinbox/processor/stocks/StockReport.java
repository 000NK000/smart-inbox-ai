package com.smartinbox.processor.stocks;
import jakarta.persistence.*;

@Entity @Table(name="stock_analysis_report")
public class StockReport {
    @Id public String id;
    @Column(nullable=false,length=200) public String title;
    @Column(nullable=false,length=20) public String scope;
    @Column(length=36) public String watchId;
    @Column(nullable=false,length=100) public String model;
    @Lob @Column(nullable=false) public String body;
    public long createdAt;
    public long dataAsOf;
}
