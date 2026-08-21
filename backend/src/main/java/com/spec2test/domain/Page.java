package com.spec2test.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "page", uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "page_no"}))
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    @Column(nullable = false)
    private byte[] png;

    @Column(name = "text_layer", nullable = false)
    private String textLayer = "";

    @Column(name = "vision_caption")
    private String visionCaption;

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public byte[] getPng() {
        return png;
    }

    public void setPng(byte[] png) {
        this.png = png;
    }

    public String getTextLayer() {
        return textLayer;
    }

    public void setTextLayer(String textLayer) {
        this.textLayer = textLayer;
    }

    public String getVisionCaption() {
        return visionCaption;
    }

    public void setVisionCaption(String visionCaption) {
        this.visionCaption = visionCaption;
    }
}
