package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.services.HcmNationalityClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/econtracts/lookups")
@RequiredArgsConstructor
@Slf4j
public class LookupController {

    private final HcmNationalityClient hcmClient;

    @GetMapping("/nationalities")
    public ApiResponse<List<HcmNationalityClient.NationalityItem>> getNationalities() {
        return ApiResponses.ok(hcmClient.getAll(), "OK");
    }
}

