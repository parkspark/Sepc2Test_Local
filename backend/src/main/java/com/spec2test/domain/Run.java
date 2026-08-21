package com.spec2test.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "run")
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "spec_name", nullable = false)
    private String specName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunPhase phase = RunPhase.CREATED;

    @Column(name = "needs_human_reason")
    private String needsHumanReason;

    @Column(name = "done_summary")
    private String doneSummary;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public String getSpecName() {
        return specName;
    }

    public void setSpecName(String specName) {
        this.specName = specName;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public RunPhase getPhase() {
        return phase;
    }

    public void setPhase(RunPhase phase) {
        this.phase = phase;
    }

    public String getNeedsHumanReason() {
        return needsHumanReason;
    }

    public void setNeedsHumanReason(String needsHumanReason) {
        this.needsHumanReason = needsHumanReason;
    }

    public String getDoneSummary() {
        return doneSummary;
    }

    public void setDoneSummary(String doneSummary) {
        this.doneSummary = doneSummary;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
