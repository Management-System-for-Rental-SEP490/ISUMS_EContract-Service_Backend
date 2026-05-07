package com.isums.contractservice.domains.dtos;

import java.time.Instant;
import java.util.UUID;

/**
 * House currently rented but eligible for deposit-booking: the active contract
 * is ending soon, the tenant has not requested renewal, and no relocation is in
 * flight. The new tenant can deposit early to reserve the house and physically
 * move in once the current tenant hands over.
 *
 * <p>{@code availableFrom} is the contract end date plus a small handover/
 * inspection buffer (defaults to {@code depositRefundDays} or 7 days).</p>
 */
public record DepositBookableHouseDto(
        UUID houseId,
        String houseName,
        String houseAddress,
        String city,
        String commune,
        String ward,
        Instant availableFrom,
        Instant currentContractEndAt
) {}
