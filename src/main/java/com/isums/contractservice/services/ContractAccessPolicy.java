package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractAccessPolicy {

    private final HouseGrpcClient houseGrpc;
    private final UserGrpcClient userGrpc;

    public enum Role { LANDLORD, MANAGER, TENANT, TECHNICAL_STAFF, NONE }

    public record Principal(Role role, UUID actorId) {}

    public Principal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return new Principal(Role.NONE, null);

        UUID keycloakId;
        try {
            keycloakId = UUID.fromString(auth.getName());
        } catch (IllegalArgumentException ex) {
            return new Principal(Role.NONE, null);
        }

        if (hasAuthority(auth, "ROLE_LANDLORD")) {
            return new Principal(Role.LANDLORD, keycloakId);
        }

        UUID internalId;
        try {
            UserResponse user = userGrpc.getUserIdAndRoleByKeyCloakId(keycloakId.toString());
            internalId = UUID.fromString(user.getId());
        } catch (Exception ex) {
            log.warn("[AccessPolicy] Failed to resolve internal id for keycloakId={}: {}", keycloakId, ex.getMessage());
            return new Principal(Role.NONE, keycloakId);
        }

        if (hasAuthority(auth, "ROLE_MANAGER")) return new Principal(Role.MANAGER, internalId);
        if (hasAuthority(auth, "ROLE_TENANT")) return new Principal(Role.TENANT, internalId);
        if (hasAuthority(auth, "ROLE_TECHNICAL_STAFF")) return new Principal(Role.TECHNICAL_STAFF, internalId);
        return new Principal(Role.NONE, internalId);
    }

    public void requireReadAccess(EContract contract) {
        Principal p = currentPrincipal();
        switch (p.role()) {
            case LANDLORD -> {  }
            case MANAGER -> {
                Set<UUID> managed = getManagedHouseIds(p.actorId());
                if (!managed.contains(contract.getHouseId())) {
                    throw new AccessDeniedException(
                            "Contract is outside the region you manage (contractId=" + contract.getId() + ")");
                }
            }
            case TENANT -> {
                if (!contract.getUserId().equals(p.actorId())) {
                    throw new AccessDeniedException("You may only access your own contracts");
                }
            }
            case TECHNICAL_STAFF, NONE -> throw new AccessDeniedException("No permission to access this contract");
        }
    }

    public void requireWriteAccess(EContract contract) {
        Principal p = currentPrincipal();
        switch (p.role()) {
            case LANDLORD -> {  }
            case MANAGER -> {
                Set<UUID> managed = getManagedHouseIds(p.actorId());
                if (!managed.contains(contract.getHouseId())) {
                    throw new AccessDeniedException(
                            "Contract is outside the region you manage");
                }
            }
            default -> throw new AccessDeniedException("Only LANDLORD or MANAGER may edit the contract");
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

