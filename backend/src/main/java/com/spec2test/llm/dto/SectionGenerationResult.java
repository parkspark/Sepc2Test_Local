package com.spec2test.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SectionGenerationResult(
        @JsonProperty(value = "elements", required = true) List<String> elements,
        @JsonProperty(value = "test_cases", required = true) List<TestCaseEntry> testCases,
        @JsonProperty(value = "questions", required = true) List<QuestionEntry> questions) {
}
