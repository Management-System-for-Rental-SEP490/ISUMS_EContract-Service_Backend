package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;

public record FileInfoDto(
        String filePath,
        byte[] file,
        @NotBlank String fileName
) {}

