package com.isums.contractservice.controllers;

import com.isums.contractservice.exceptions.GlobalExceptionHandler;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.abstracts.DashboardService;
import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.infrastructures.abstracts.PowerCutService;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("EContractController")
class EContractControllerTest {

    @Mock private EContractService service;
    @Mock private DashboardService dashboardService;
    @Mock private VnptEContractClient vnptClient;
    @Mock private PowerCutService powerCutService;
    @Mock private ContractTerminationService terminationService;

    @InjectMocks private EContractController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST debug ready notification endpoint delegates to service")
    void replayReadyNotification() throws Exception {
        UUID contractId = UUID.randomUUID();

        mvc.perform(post("/api/econtracts/{contractId}/notifications/ready-for-landlord-signature/test", contractId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã replay notification landlord ký"));

        verify(service).triggerReadyForLandlordSignatureNotification(contractId);
    }

    @Test
    @DisplayName("POST debug ready notification endpoint returns 422 for invalid contract state")
    void replayReadyNotificationInvalidState() throws Exception {
        UUID contractId = UUID.randomUUID();
        doThrow(new IllegalStateException("Chỉ replay notification khi hợp đồng đang ở READY. Hiện tại: DRAFT"))
                .when(service).triggerReadyForLandlordSignatureNotification(contractId);

        mvc.perform(post("/api/econtracts/{contractId}/notifications/ready-for-landlord-signature/test", contractId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("Chỉ replay notification khi hợp đồng đang ở READY. Hiện tại: DRAFT"));
    }
}
