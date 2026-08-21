package com.spec2test.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SectionEntry(
        @JsonProperty(value = "no", required = true) String no,
        @JsonProperty(value = "title", required = true) String title,
        @JsonProperty(value = "page_start", required = true) Integer pageStart,
        @JsonProperty(value = "page_end", required = true) Integer pageEnd,
        @JsonProperty(value = "category_hint", required = true) String categoryHint) {
}
