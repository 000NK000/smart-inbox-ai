package com.smartinbox.processor.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "practice_solution_image", indexes = @Index(name = "idx_solution_image_number", columnList = "problemNumber"))
public class PracticeSolutionImage {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Integer problemNumber;
    @Column(nullable = false, length = 24) private String mimeType;
    @Column(nullable = false, length = 200) private String caption = "";
    @Column(nullable = false) private int position;
    @Lob @Column(nullable = false, columnDefinition = "BLOB") private byte[] data;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public Integer getProblemNumber() { return problemNumber; }
    public void setProblemNumber(Integer value) { problemNumber = value; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String value) { mimeType = value; }
    public String getCaption() { return caption; }
    public void setCaption(String value) { caption = value; }
    public int getPosition() { return position; }
    public void setPosition(int value) { position = value; }
    public byte[] getData() { return data; }
    public void setData(byte[] value) { data = value; }
}
