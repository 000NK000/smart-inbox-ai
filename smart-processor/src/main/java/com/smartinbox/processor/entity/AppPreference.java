package com.smartinbox.processor.entity;
import jakarta.persistence.*;
@Entity @Table(name="app_preference")
public class AppPreference {
    @Id @Column(length=60) private String name;
    @Column(name="preference_value",length=100) private String value;
    public AppPreference() {}
    public AppPreference(String name,String value) { this.name=name; this.value=value; }
    public String getName(){return name;}
    public String getValue(){return value;}
}
