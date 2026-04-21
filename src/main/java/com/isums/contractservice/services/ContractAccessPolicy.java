package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Role + region-aware ownership check for contract access.
 *
 * Scope rules (reflect domain spec 2026-04-21):
 * - LANDLORD: sees/edits everything (single company/individual owner).
 * - MANAGER: scoped to regions they manage. House-service gRPC resolves
 *   {@code manager.id → list of house IDs in managed regions}.
 * - TENANT: sees/edits only their own contracts (userId == actor).
 * - TECHNICAL_STAFF: no direct contract access (they work with issue tickets);
 *   if needed later, wire via region_staff.staff_id → region → houses.
 * - No role / unauthenticated: denied.
 *
 * This is read via SecurityContextHolder so services don't need to plumb
 * Authentication through every call. Tests inject via
 * {@link org.springframework.security.test.context.support.WithMockUser}
 * or manual {@code SecurityContextHolder.setContext(...)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractAccessPolicy {

    private final HouseGrpcClient houseGrpc;

    public enum Role { LANDLORD, MANAGER, TENANT, TECHNICAL_STAFF, NONE }

    public record Principal(Role role, UUID actorId) {}

    public Principal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return new Principal(Role.NONE, null);

        UUID actorId;
        try {
            actorId = UUID.fromString(auth.getName());
        } catch (IllegalArgumentException ex) {
            return new Principal(Role.NONE, null);
        }

        if (hasAuthority(auth, "ROLE_LANDLORD")) return new Principal(Role.LANDLORD, actorId);
        if (hasAuthority(auth, "ROLE_MANAGER")) return new Principal(Role.MANAGER, actorId);
        if (hasAuthority(auth, "ROLE_TENANT")) return new Principal(Role.TENANT, actorId);
        if (hasAuthority(auth, "ROLE_TECHNICAL_STAFF")) return new Principal(Role.TECHNICAL_STAFF, actorId);
        return new Principal(Role.NONE, actorId);
    }

    /**
     * Throws AccessDeniedException if the current principal cannot read this contract.
     * LANDLORD always passes; MANAGER must manage the house's region; TENANT must be the
     * contract's user.
     */
    public void requireReadAccess(EContract contract) {
        Principal p = currentPrincipal();
        switch (p.role()) {
            case LANDLORD -> { /* allowed */ }
            case MANAGER -> {
                Set<UUID> managed = getManagedHouseIds(p.actorId());
                if (!managed.contains(contract.getHouseId())) {
                    throw new AccessDeniedException(
                            "Hợp đồng nằm ngoài region bạn quản lý (contractId=" + contract.getId() + ")");
                }
            }
            case TENANT -> {
                if (!contract.getUserId().equals(p.actorId())) {
                    throw new AccessDeniedException("Chỉ truy cập được hợp đồng của chính mình");
                }
            }
            case TECHNICAL_STAFF, NONE -> throw new AccessDeniedException("Không có quyền truy cập hợp đồng");
        }
    }

    /**
     * Write access: LANDLORD + MANAGER only. TENANT can't edit contracts
     * (only confirm via magic-token flow which uses a separate gate).
     */
    public void requireWriteAccess(EContract contract) {
        Principal p = currentPrincipal();
        switch (p.role()) {
            case LANDLORD -> { /* allowed */ }
            case MANAGER -> {
                Set<UUID> managed = getManagedHouseIds(p.actorId());
                if (!managed.contains(contract.getHouseId())) {
                    throw new AccessDeniedException(
                            "Hợp đồng nằm ngoài region bạn quản lý");
                }
            }
            default -> throw new AccessDeniedException("Chỉ LANDLORD hoặc MANAGER được chỉnh sửa hợp đồng");
        }
    }

    @Cacheable(value = "managedHouseIds", key = "#managerId", unless = "#result == null || #result.isEmpty()")
    public Set<UUID> getManagedHouseIds(UUID managerId) {
        try {
            return houseGrpc.getManagedHouseIds(managerId);
        } catch (Exception ex) {
            log.warn("[AccessPolicy] Failed to resolve managed houses for managerId={}: {}",
                    managerId, ex.getMessage());
            return Set.of();
        }
    }

    private static boolean hasAuthority(Authentication auth, String role) {
        return auth.getAuthorities() != null
                && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }
}
