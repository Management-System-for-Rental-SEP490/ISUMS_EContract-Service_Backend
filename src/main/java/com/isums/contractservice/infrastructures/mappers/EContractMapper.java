package com.isums.contractservice.infrastructures.mappers;

import com.isums.contractservice.domains.dtos.DetailedAddressDto;
import com.isums.contractservice.domains.dtos.EContractDto;
import com.isums.contractservice.domains.dtos.MeterReadingsDto;
import com.isums.contractservice.domains.dtos.UpdateEContractRequest;
import com.isums.contractservice.domains.entities.EContract;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface EContractMapper {

    // -----------------------------------------------------------------------
    // Entity → DTO: direct field-for-field; `pdfUrl` is computed at service
    // layer (presigned URL) so we ignore it here.
    // -----------------------------------------------------------------------
    @Mapping(target = "pdfUrl", ignore = true)
    @Mapping(target = "updatePdfUrl", ignore = true)
    EContractDto contractToDto(EContract contract);

    List<EContractDto> contractsToDtoList(List<EContract> contracts);

    // -----------------------------------------------------------------------
    // Update request → Entity: partial patch, nulls ignored.
    // UpdateRequest.rentAmount maps to Entity.price (legacy naming retained
    // in the entity to avoid a cross-cutting rename right now).
    // DTO address/meter nested objects flatten into jsonb maps via helpers.
    // -----------------------------------------------------------------------
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(source = "detailedAddress", target = "detailedAddress", qualifiedByName = "addressToMap")
    @Mapping(source = "meterReadingsStart", target = "meterReadingsStart", qualifiedByName = "meterToMap")
    // Columns that must not be touched by an update (handled by service layer)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "houseId", ignore = true)
    @Mapping(target = "regionId", ignore = true)
    @Mapping(target = "startAt", ignore = true)
    @Mapping(target = "endAt", ignore = true)
    @Mapping(target = "tenantType", ignore = true)
    @Mapping(target = "documentId", ignore = true)
    @Mapping(target = "documentNo", ignore = true)
    @Mapping(target = "snapshotKey", ignore = true)
    @Mapping(target = "cccdFrontKey", ignore = true)
    @Mapping(target = "cccdBackKey", ignore = true)
    @Mapping(target = "cccdVerifiedAt", ignore = true)
    @Mapping(target = "passportFrontKey", ignore = true)
    @Mapping(target = "passportVerifiedAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "terminatedAt", ignore = true)
    @Mapping(target = "terminatedReason", ignore = true)
    @Mapping(target = "terminatedBy", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    void patch(UpdateEContractRequest req, @MappingTarget EContract contract);

    @Named("addressToMap")
    default Map<String, String> addressToMap(DetailedAddressDto dto) {
        return dto == null ? null : dto.asMap();
    }

    @Named("meterToMap")
    default Map<String, Object> meterToMap(MeterReadingsDto dto) {
        if (dto == null) return null;
        Map<String, Object> m = new HashMap<>(dto.asMap());
        return m;
    }
}
