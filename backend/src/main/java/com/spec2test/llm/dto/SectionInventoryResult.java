package com.spec2test.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SectionInventoryResult(
        @JsonProperty(value = "sections", required = true) List<SectionEntry> sections) {
}
