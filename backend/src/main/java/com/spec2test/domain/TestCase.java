package com.spec2test.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "test_case")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(name = "seq_in_section", nullable = false)
    private Integer seqInSection;

    @Column(name = "global_no")
    private Integer globalNo;

    @Column(name = "category_major", nullable = false)
    private String categoryMajor = "";

    @Column(name = "category_mid", nullable = false)
    private String categoryMid = "";

    @Column(name = "category_minor", nullable = false)
    private String categoryMinor = "";

    @Column(name = "test_item", nullable = false)
    private String testItem = "";

    @Column(nullable = false)
    private String precondition = "";

    @Column(name = "test_steps", nullable = false)
    private String testSteps = "";

    @Column(name = "expected_result", nullable = false)
    private String expectedResult = "";

    @Column(nullable = false)
    private String remark = "";

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getSectionId() {
        return sectionId;
    }

    public void setSectionId(Long sectionId) {
        this.sectionId = sectionId;
    }

    public Integer getSeqInSection() {
        return seqInSection;
    }

    public void setSeqInSection(Integer seqInSection) {
        this.seqInSection = seqInSection;
    }

    public Integer getGlobalNo() {
        return globalNo;
    }

    public void setGlobalNo(Integer globalNo) {
        this.globalNo = globalNo;
    }

    public String getCategoryMajor() {
        return categoryMajor;
    }

    public void setCategoryMajor(String categoryMajor) {
        this.categoryMajor = categoryMajor;
    }

    public String getCategoryMid() {
        return categoryMid;
    }

    public void setCategoryMid(String categoryMid) {
        this.categoryMid = categoryMid;
    }

    public String getCategoryMinor() {
        return categoryMinor;
    }

    public void setCategoryMinor(String categoryMinor) {
        this.categoryMinor = categoryMinor;
    }

    public String getTestItem() {
        return testItem;
    }

    public void setTestItem(String testItem) {
        this.testItem = testItem;
    }

    public String getPrecondition() {
        return precondition;
    }

    public void setPrecondition(String precondition) {
        this.precondition = precondition;
    }

    public String getTestSteps() {
        return testSteps;
    }

    public void setTestSteps(String testSteps) {
        this.testSteps = testSteps;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public void setExpectedResult(String expectedResult) {
        this.expectedResult = expectedResult;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
