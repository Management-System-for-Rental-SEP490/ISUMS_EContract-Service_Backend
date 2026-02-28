package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VnptResult<T> {

    private Boolean success;
    private String message;
    private T data;

    public static <T> VnptResult<T> error(String message) {
        return new VnptResult<>(false, message, null);
    }

    public static <T> VnptResult<T> success(T data) {
        return new VnptResult<>(true, null, data);
    }
}
