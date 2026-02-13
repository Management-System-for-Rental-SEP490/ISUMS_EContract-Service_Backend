package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("createdDate") Instant createdDate,
        @JsonProperty("lastModifiedDate") Instant lastModifiedDate,
        @JsonProperty("completedDate") Instant completedDate,

        @JsonProperty("no") String no,
        @JsonProperty("subject") String subject,
        @JsonProperty("status") StatusInfo status,
        @JsonProperty("description") String description,

        @JsonProperty("waitingProcess") WaitingProcessDto waitingProcess,
        @JsonProperty("processInOrder") boolean processInOrder,

        @JsonProperty("type") DocumentType type,
        @JsonProperty("file") FileInfo file,

        @JsonProperty("downloadUrl") String downloadUrl,

        @JsonProperty("receiveOtpMethod") Integer receiveOtpMethod,
        @JsonProperty("receiveOtpPhone") String receiveOtpPhone,
        @JsonProperty("receiveOtpEmail") String receiveOtpEmail,

        @JsonProperty("requireOtpConfirmation") Boolean requireOtpConfirmation
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatusInfo(
            @JsonProperty("value") int value,
            @JsonProperty("description") String description
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WaitingProcessDto(
            @JsonProperty("id") UUID id,
            @JsonProperty("createdDate") Instant createdDate,
            @JsonProperty("isOrder") boolean isOrder,
            @JsonProperty("orderNo") int orderNo,
            @JsonProperty("pageSign") int pageSign,
            @JsonProperty("position") String position,
            @JsonProperty("accessPermission") StatusInfo accessPermission,
            @JsonProperty("status") StatusInfo status
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentType(
            @JsonProperty("id") int id,
            @JsonProperty("code") String code,
            @JsonProperty("name") String name
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileInfo(
            @JsonProperty("name") String name,
            @JsonProperty("size") long size
    ) {
    }
}
