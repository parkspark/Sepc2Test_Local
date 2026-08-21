package com.spec2test.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuestionEntry(
        @JsonProperty(value = "text", required = true) String text,
        @JsonProperty(value = "source", required = true) String source) {
}
