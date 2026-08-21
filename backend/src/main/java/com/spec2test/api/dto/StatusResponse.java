package com.spec2test.api.dto;

import java.util.Map;

public record StatusResponse(String status, String message, Map<String, Object> progress) {
}
