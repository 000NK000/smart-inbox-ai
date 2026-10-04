package com.smartinbox.processor.stocks;
import jakarta.persistence.*;

@Entity @Table(name="stock_watch_entry",uniqueConstraints=@UniqueConstraint(columnNames={"symbol","exchange_code"}))
public class StockWatchEntry {
    @Id public String id;
    @Column(nullable=false,length=25) public String symbol;
    @Column(name="exchange_code",nullable=false,length=30) public String exchange;
    @Column(nullable=false,length=150) public String name;
    @Column(nullable=false,length=4000) public String notes;
    public long createdAt;
    public long updatedAt;
    @Version public Long version;
}
