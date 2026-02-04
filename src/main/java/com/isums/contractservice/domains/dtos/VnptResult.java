package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VnptResult<T> {

    private Boolean success;
    private String message;
    private T data;

    public static <T> VnptResult<T> error(String message) {
        return new  VnptResult<>(false, message, null);
    }
}
