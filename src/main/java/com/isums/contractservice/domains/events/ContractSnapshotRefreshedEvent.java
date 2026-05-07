package com.isums.contractservice.domains.events;

import java.util.UUID;

/**
 * Intra-service Spring event (NOT Kafka) fired by the async snapshot
 * refresh service after each attempt finishes — whether the snapshot
 * actually changed or not. Lets {@code EContractServiceImpl} wait to
 * emit the Kafka {@code contract-completed-topic} event until we know
 * whether the final signed PDF URL is available.
 *
 * <p>Kept as a record because the data is immutable and this class
 * crosses thread boundaries (async executor → @EventListener).
 *
 * @param contractId      the contract
 * @param signingEvent    diagnostic label (e.g. "tenant signed (final)")
 * @param isFinal         true if this refresh followed a final (tenant)
 *                        signature — listener should emit
 *                        contract-completed-topic
 * @param snapshotUpdated true if the S3 snapshotKey was overwritten
 *                        with fresh bytes; false means VNPT kept
 *                        returning the same PDF across all retries
 * @param presignedPdfUrl freshly-presigned URL (TTL 7d) of the new
 *                        snapshot, or {@code null} if refresh failed
 *                        entirely
 */
public record ContractSnapshotRefreshedEvent(
        UUID contractId,
        String signingEvent,
        boolean isFinal,
        boolean snapshotUpdated,
        String presignedPdfUrl
) { }
