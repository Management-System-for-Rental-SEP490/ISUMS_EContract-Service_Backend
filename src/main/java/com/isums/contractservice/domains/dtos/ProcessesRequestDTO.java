package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessesRequestDTO(

        @JsonProperty("OrderNo")
        int orderNo,

        @JsonProperty("ProcessedByUserCode")
        String processedByUserCode,

        @JsonProperty("AccessPermissionCode")
        String accessPermissionCode,

        @JsonProperty("Position")
        String position,

        @JsonProperty("PageSign")
        int pageSign

) {
}