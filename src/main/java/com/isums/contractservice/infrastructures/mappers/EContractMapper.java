package com.isums.contractservice.infrastructures.mappers;

import com.isums.contractservice.domains.dtos.EContractDto;
import com.isums.contractservice.domains.entities.EContract;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EContractMapper {
    EContractDto contractToDto(EContract contract);
    List<EContractDto> contractsToDtoList(List<EContract> contracts);
}
