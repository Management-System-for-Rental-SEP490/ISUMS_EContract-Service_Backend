package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginVnptDto(
        String username,
        String password,
        @Nullable Integer companyId
) {
    public LoginVnptDto(String username, String password) {
        this(username, password, null);
    }
}
