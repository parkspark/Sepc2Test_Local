package com.spec2test.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TestCaseEntry(
        @JsonProperty(value = "대분류", required = true) String categoryMajor,
        @JsonProperty(value = "중분류", required = true) String categoryMid,
        @JsonProperty(value = "소분류", required = true) String categoryMinor,
        @JsonProperty(value = "테스트 항목", required = true) String testItem,
        @JsonProperty(value = "사전조건", required = true) String precondition,
        @JsonProperty(value = "테스트 스텝", required = true) String testSteps,
        @JsonProperty(value = "기대결과", required = true) String expectedResult,
        @JsonProperty(value = "비고", required = true) String remark) {
}
