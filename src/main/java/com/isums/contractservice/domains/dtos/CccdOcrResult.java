package com.isums.contractservice.domains.dtos;

import tools.jackson.databind.JsonNode;

public record CccdOcrResult(
        String identityNumber,
        String fullName,
        String dateOfBirth,
        String gender,
        String placeOfOrigin,
        String address,
        String issueDate,
        String issuePlace
) {
    public static CccdOcrResult from(JsonNode node) {
        return new CccdOcrResult(
                nullableText(node, "identityNumber"),
                nullableText(node, "fullName"),
                nullableText(node, "dateOfBirth"),
                nullableText(node, "gender"),
                nullableText(node, "placeOfOrigin"),
                nullableText(node, "address"),
                nullableText(node, "issueDate"),
                nullableText(node, "issuePlace")
        );
    }

    private static String nullableText(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isString() && !v.asString().isBlank()) ? v.asString().trim() : null;
    }
}