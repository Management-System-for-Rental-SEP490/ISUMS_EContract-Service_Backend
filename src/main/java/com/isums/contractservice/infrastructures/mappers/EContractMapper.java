package com.isums.contractservice.infrastructures.mappers;

import com.isums.contractservice.domains.dtos.EContractDto;
import com.isums.contractservice.domains.dtos.UpdateEContractRequest;
import com.isums.contractservice.domains.entities.EContract;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EContractMapper {
    EContractDto contractToDto(EContract contract);
    List<EContractDto> contractsToDtoList(List<EContract> contracts);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void patch (UpdateEContractRequest req, @MappingTarget EContract contract);
}
