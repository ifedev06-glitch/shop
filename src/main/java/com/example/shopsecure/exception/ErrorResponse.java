// ErrorResponse.java
package com.example.shopsecure.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL) // hides null fields like `details`
public class ErrorResponse {
    private int status;
    private String error;
    private Map<String, String> details; // only populated for validation errors
    private LocalDateTime timestamp;
}