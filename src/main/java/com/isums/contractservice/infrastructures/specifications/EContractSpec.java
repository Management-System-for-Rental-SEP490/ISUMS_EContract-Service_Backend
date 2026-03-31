package com.isums.contractservice.infrastructures.specifications;

import com.isums.contractservice.domains.entities.EContract;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

public class EContractSpec {
    public static Specification<EContract> beforeCursor(Instant cursor) {
        return (root, query, cb) ->
                cursor == null
                        ? cb.conjunction()
                        : cb.lessThan(root.get("createdAt"), cursor);
    }
}

