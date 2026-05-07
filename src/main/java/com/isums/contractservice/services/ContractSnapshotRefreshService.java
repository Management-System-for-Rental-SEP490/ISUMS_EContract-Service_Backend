package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.VnptDocumentDto;
import com.isums.contractservice.domains.dtos.VnptResult;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.events.ContractSnapshotRefreshedEvent;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.isums.contractservice.configurations.AsyncConfig.SNAPSHOT_REFRESH_EXECUTOR;

/**
 * Background service that refreshes the S3 snapshot of a contract from
 * VNPT after each signing event.
 *
 * <p><b>Why this exists as a separate bean (not a method on
 * EContractServiceImpl):</b>
 * <ul>
 *   <li>{@code @Async} self-invocation doesn't work — Spring proxies
 *       only intercept external calls. Putting the async method in a
 *       different bean is the standard workaround.</li>
 *   <li>VNPT's signing API returns as soon as the crypto signature is
 *       recorded, but visual rendering of the stamp into the PDF can
 *       lag 1–3s. Running the refresh inline in the sign transaction
 *       either returns a stale PDF (the bug reported 2026-04-23) or
 *       holds the sign API response for several seconds. Neither is
 *       acceptable in prod.</li>
 *   <li>Isolated thread pool ({@code snapshotRefreshExecutor}) bounds
 *       the blast radius of a VNPT outage — at worst the queue fills
 *       up and new refresh tasks are rejected; the sign API itself
 *       stays fast.</li>
 * </ul>
 *
 * <p><b>Retry policy:</b> up to 5 attempts with delays
 * {@code 0 → 2 → 5 → 10 → 30} seconds (total ≤ 47s). Each attempt:
 * <ol>
 *   <li>Fetch document metadata from VNPT ({@code GET /api/documents/{id}}).</li>
 *   <li>Download the PDF bytes from {@code downloadUrl}.</li>
 *   <li>SHA-256 the bytes and compare to the current snapshot's hash.
 *       If identical, VNPT hasn't finalised the stamp yet — wait and
 *       retry. If different, the bytes are genuinely new; upload to S3
 *       and mark done.</li>
 * </ol>
 *
 * <p><b>Failure mode:</b> if all 5 attempts return the same hash as the
 * existing snapshot (VNPT took longer than 47s to render — rare), the
 * snapshot stays stale. We fire the {@link ContractSnapshotRefreshedEvent}
 * anyway with {@code snapshotUpdated=false} so listeners (e.g.
 * contract-completed emit) can proceed with what they have. An admin
 * endpoint ({@code POST /api/econtracts/{id}/snapshot/refresh}) lets
 * support retry on demand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractSnapshotRefreshService {

    /** Per-attempt delay in milliseconds. Total wall clock ≤ sum = 47s. */
    private static final long[] RETRY_DELAYS_MS = {0L, 2_000L, 5_000L, 10_000L, 30_000L};

    private static final int PRESIGN_TTL_MINUTES = 7 * 24 * 60;

    private final EContractRepository contractRepo;
    private final S3Service s3;
    private final VnptEContractClient vnptClient;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    /**
     * Trigger an async refresh. Returns immediately; the actual work
     * runs on the {@code snapshotRefreshExecutor} thread pool.
     *
     * <p>Callers (sign controllers, admin endpoints) should invoke
     * this <em>after transaction commit</em> via
     * {@code TransactionSynchronizationManager.registerSynchronization}
     * so a transaction rollback doesn't leave an orphan async task that
     * references a contract in an inconsistent state.
     *
     * @param contractId   the contract whose snapshot needs refresh
     * @param signingEvent diagnostic label for logs + metrics
     * @param isFinal      {@code true} if this follows the final
     *                     (tenant) signature — the resulting event
     *                     triggers {@code contract-completed-topic}
     *                     emission downstream
     */
    @Async(SNAPSHOT_REFRESH_EXECUTOR)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshAsync(UUID contractId, String signingEvent, boolean isFinal) {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean updated = false;
        String presignedUrl = null;

        try {
            EContract contract = contractRepo.findById(contractId).orElse(null);
            if (contract == null) {
                log.warn("[SnapshotRefresh] {} contract gone contractId={}", signingEvent, contractId);
                return;
            }

            updated = runRetryLoop(contract, signingEvent);

            if (contract.getSnapshotKey() != null) {
                try {
                    presignedUrl = s3.presignedUrl(contract.getSnapshotKey(), PRESIGN_TTL_MINUTES);
                } catch (Exception e) {
                    // Non-fatal — email template can render without the link.
                    log.warn("[SnapshotRefresh] {} presign failed contractId={}: {}",
                            signingEvent, contractId, e.getMessage());
                }
            }

            meterRegistry.counter(
                    "econtract_snapshot_refresh_total",
                    "outcome", updated ? "updated" : "unchanged",
                    "event", sanitize(signingEvent)
            ).increment();

        } catch (Exception e) {
            log.error("[SnapshotRefresh] {} async refresh crashed contractId={}",
                    signingEvent, contractId, e);
            meterRegistry.counter(
                    "econtract_snapshot_refresh_total",
                    "outcome", "error",
                    "event", sanitize(signingEvent)
            ).increment();
        } finally {
            sample.stop(meterRegistry.timer(
                    "econtract_snapshot_refresh_duration",
                    "event", sanitize(signingEvent)
            ));

            // Always fire the completion event — listeners (like the
            // contract-completed emitter) rely on it to stop waiting.
            // A null presignedUrl means either "VNPT didn't update in
            // time" or "everything failed"; downstream consumers are
            // defensive (see payment-service's CONTRACT_COMPLETED
            // email fix 2026-04-23).
            eventPublisher.publishEvent(new ContractSnapshotRefreshedEvent(
                    contractId, signingEvent, isFinal, updated, presignedUrl));
        }
    }

    /**
     * Synchronous variant for the admin manual-refresh endpoint.
     * Blocks up to ~47s; callers must pair with a reasonable HTTP
     * timeout. Does NOT fire a Spring event (admin flow has different
     * downstream — just returns the result).
     *
     * @return {@code true} if a new snapshot was uploaded
     */
    @Transactional
    public boolean refreshSync(UUID contractId, String signingEvent) {
        EContract contract = contractRepo.findById(contractId).orElseThrow(
                () -> new IllegalArgumentException("Contract not found: " + contractId));
        return runRetryLoop(contract, signingEvent);
    }

    // ── Core retry loop ───────────────────────────────────────────────

    private boolean runRetryLoop(EContract contract, String signingEvent) {
        final UUID contractId = contract.getId();
        byte[] oldHash = captureOldSnapshotHash(contract);

        for (int attempt = 1; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            long delay = RETRY_DELAYS_MS[attempt - 1];
            try {
                if (delay > 0) {
                    Thread.sleep(delay);
                }

                VnptResult<VnptDocumentDto> docResult = vnptClient.getEContractById(
                        contract.getDocumentId(), vnptClient.getToken());

                if (docResult == null
                        || docResult.getData() == null
                        || docResult.getData().downloadUrl() == null) {
                    log.warn("[SnapshotRefresh] {} attempt {}/{} — no downloadUrl contractId={}",
                            signingEvent, attempt, RETRY_DELAYS_MS.length, contractId);
                    continue;
                }

                byte[] pdfBytes = vnptClient.downloadSignedPdf(docResult.getData().downloadUrl());
                if (pdfBytes == null || pdfBytes.length == 0) {
                    log.warn("[SnapshotRefresh] {} attempt {}/{} — empty PDF contractId={}",
                            signingEvent, attempt, RETRY_DELAYS_MS.length, contractId);
                    continue;
                }

                byte[] newHash = sha256(pdfBytes);
                if (oldHash != null && Arrays.equals(oldHash, newHash)) {
                    log.info("[SnapshotRefresh] {} attempt {}/{} — PDF unchanged (same hash), waiting for VNPT render",
                            signingEvent, attempt, RETRY_DELAYS_MS.length);
                    continue;
                }

                String oldKey = contract.getSnapshotKey();
                String newKey = s3.uploadContractPdf(pdfBytes, contractId);
                contract.setSnapshotKey(newKey);
                contractRepo.save(contract);

                // Delete AFTER save so a crash between upload + save
                // doesn't lose the old snapshot — worst case we leak
                // one S3 object until a sweep job reclaims it.
                if (oldKey != null && !Objects.equals(oldKey, newKey)) {
                    try {
                        s3.deleteIfExists(oldKey);
                    } catch (Exception e) {
                        log.warn("[SnapshotRefresh] orphan snapshot cleanup failed key={}: {}",
                                oldKey, e.getMessage());
                    }
                }

                meterRegistry.counter(
                        "econtract_snapshot_refresh_attempts",
                        "outcome", "success",
                        "attempt", String.valueOf(attempt)
                ).increment();
                log.info("[SnapshotRefresh] {} snapshot refreshed attempt={}/{} key={} size={}KB contractId={}",
                        signingEvent, attempt, RETRY_DELAYS_MS.length, newKey,
                        pdfBytes.length / 1024, contractId);
                return true;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[SnapshotRefresh] {} interrupted contractId={}", signingEvent, contractId);
                return false;
            } catch (Exception e) {
                log.warn("[SnapshotRefresh] {} attempt {}/{} failed contractId={}: {}",
                        signingEvent, attempt, RETRY_DELAYS_MS.length, contractId, e.getMessage());
                meterRegistry.counter(
                        "econtract_snapshot_refresh_attempts",
                        "outcome", "error",
                        "attempt", String.valueOf(attempt)
                ).increment();
            }
        }

        log.error("[SnapshotRefresh] {} all {} attempts failed contractId={} — snapshot left stale",
                signingEvent, RETRY_DELAYS_MS.length, contractId);
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private byte[] captureOldSnapshotHash(EContract contract) {
        if (contract.getSnapshotKey() == null) return null;
        try {
            return sha256(s3.downloadBytes(contract.getSnapshotKey()));
        } catch (Exception e) {
            // If old snapshot is gone or unreadable (race with S3 sweep),
            // treat every fetched PDF as new. Slightly less efficient
            // but correctness-preserving.
            log.debug("[SnapshotRefresh] cannot hash old snapshot key={}: {}",
                    contract.getSnapshotKey(), e.getMessage());
            return null;
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Part of every JRE since 1.4 — defensive only.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Strip spaces + parens so the string is safe as a Micrometer tag value. */
    private static String sanitize(String label) {
        return label == null ? "unknown"
                : label.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
