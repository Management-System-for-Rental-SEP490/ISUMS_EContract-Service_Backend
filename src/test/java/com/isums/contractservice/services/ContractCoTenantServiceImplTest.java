package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CoTenantDto;
import com.isums.contractservice.domains.dtos.CoTenantResponseDto;
import com.isums.contractservice.domains.entities.ContractCoTenant;
import com.isums.contractservice.domains.enums.CoTenantIdentityType;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.repositories.ContractCoTenantRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractCoTenantServiceImpl")
class ContractCoTenantServiceImplTest {

    @Mock private ContractCoTenantRepository repo;
    @Mock private EContractRepository contractRepo;
    @Mock private ContractAccessPolicy accessPolicy;

    @InjectMocks private ContractCoTenantServiceImpl service;

    private UUID contractId;
    private UUID coTenantId;

    @BeforeEach
    void setUp() {
        contractId = UUID.randomUUID();
        coTenantId = UUID.randomUUID();
    }

    private CoTenantDto sampleDto() {
        return new CoTenantDto(
                "Nguyen Van B",
                "012345678901",
                CoTenantIdentityType.CCCD,
                LocalDate.of(1990, 1, 1),
                "M",
                "VN",
                "Anh trai",
                "0901234567"
        );
    }

    private ContractCoTenant entity(UUID id, UUID cid, String name) {
        return ContractCoTenant.builder()
                .id(id).contractId(cid).fullName(name)
                .identityNumber("012345678901")
                .identityType(CoTenantIdentityType.CCCD)
                .relationship("Anh trai")
                .build();
    }

    private com.isums.contractservice.domains.entities.EContract contract(UUID cid) {
        return com.isums.contractservice.domains.entities.EContract.builder()
                .id(cid)
                .userId(UUID.randomUUID())
                .houseId(UUID.randomUUID())
                .build();
    }

    @Nested
    @DisplayName("list")
    class ListOp {
        @Test
        @DisplayName("returns co-tenants when contract exists")
        void returnsList() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            when(repo.findByContractId(contractId)).thenReturn(List.of(
                    entity(coTenantId, contractId, "Nguyen A"),
                    entity(UUID.randomUUID(), contractId, "Nguyen B")));

            List<CoTenantResponseDto> result = service.list(contractId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).fullName()).isEqualTo("Nguyen A");
        }

        @Test
        @DisplayName("throws when contract missing")
        void contractMissing() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.list(contractId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("create")
    class Create {
        @Test
        @DisplayName("persists with correct fields + contract link")
        void persists() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            when(repo.save(any(ContractCoTenant.class)))
                    .thenAnswer(inv -> {
                        ContractCoTenant c = inv.getArgument(0);
                        c.setId(coTenantId);
                        return c;
                    });

            CoTenantResponseDto out = service.create(contractId, sampleDto());

            assertThat(out.fullName()).isEqualTo("Nguyen Van B");
            assertThat(out.contractId()).isEqualTo(contractId);
            assertThat(out.identityType()).isEqualTo(CoTenantIdentityType.CCCD);

            ArgumentCaptor<ContractCoTenant> cap = ArgumentCaptor.forClass(ContractCoTenant.class);
            verify(repo).save(cap.capture());
            assertThat(cap.getValue().getContractId()).isEqualTo(contractId);
            assertThat(cap.getValue().getRelationship()).isEqualTo("Anh trai");
        }

        @Test
        @DisplayName("rejects when contract missing")
        void contractMissing() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.create(contractId, sampleDto()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {
        @Test
        @DisplayName("updates fields of matching co-tenant")
        void updates() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            ContractCoTenant existing = entity(coTenantId, contractId, "Old Name");
            when(repo.findById(coTenantId)).thenReturn(Optional.of(existing));
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.update(contractId, coTenantId, sampleDto());

            assertThat(existing.getFullName()).isEqualTo("Nguyen Van B");
            verify(repo).save(existing);
        }

        @Test
        @DisplayName("rejects cross-contract co-tenant")
        void crossContract() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            UUID otherContract = UUID.randomUUID();
            when(repo.findById(coTenantId))
                    .thenReturn(Optional.of(entity(coTenantId, otherContract, "X")));
            assertThatThrownBy(() -> service.update(contractId, coTenantId, sampleDto()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("không thuộc contract");
        }

        @Test
        @DisplayName("throws when co-tenant missing")
        void missing() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            when(repo.findById(coTenantId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.update(contractId, coTenantId, sampleDto()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {
        @Test
        @DisplayName("deletes matching co-tenant")
        void deletes() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            ContractCoTenant existing = entity(coTenantId, contractId, "X");
            when(repo.findById(coTenantId)).thenReturn(Optional.of(existing));

            service.delete(contractId, coTenantId);

            verify(repo).delete(existing);
        }

        @Test
        @DisplayName("rejects cross-contract")
        void crossContract() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            when(repo.findById(coTenantId))
                    .thenReturn(Optional.of(entity(coTenantId, UUID.randomUUID(), "X")));
            assertThatThrownBy(() -> service.delete(contractId, coTenantId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("replaceAll")
    class ReplaceAll {
        @Test
        @DisplayName("deletes existing then inserts new list")
        void replace() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            when(repo.saveAll(anyList())).thenAnswer(inv -> {
                List<ContractCoTenant> list = inv.getArgument(0);
                for (int i = 0; i < list.size(); i++) list.get(i).setId(UUID.randomUUID());
                return list;
            });

            List<CoTenantResponseDto> out = service.replaceAll(
                    contractId, List.of(sampleDto(), sampleDto()));

            verify(repo).deleteByContractId(contractId);
            assertThat(out).hasSize(2);
        }

        @Test
        @DisplayName("empty list just wipes")
        void emptyList() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));

            List<CoTenantResponseDto> out = service.replaceAll(contractId, List.of());

            verify(repo).deleteByContractId(contractId);
            verify(repo, never()).saveAll(anyList());
            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("null list just wipes")
        void nullList() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(contractId)));
            List<CoTenantResponseDto> out = service.replaceAll(contractId, null);
            verify(repo).deleteByContractId(contractId);
            assertThat(out).isEmpty();
        }
    }
}
