package com.isums.contractservice.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.contractservice.domains.dtos.LandlordProfileDto;
import com.isums.contractservice.domains.dtos.UpsertLandlordProfileRequest;
import com.isums.contractservice.exceptions.GlobalExceptionHandler;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.LandlordProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("LandlordProfileController")
class LandlordProfileControllerTest {

    @Mock private LandlordProfileService service;

    @InjectMocks private LandlordProfileController controller;

    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg","none").subject(userId.toString()).build();

        HandlerMethodArgumentResolver jwtResolver = new HandlerMethodArgumentResolver() {
            @Override public boolean supportsParameter(MethodParameter p) {
                return Jwt.class.equals(p.getParameterType());
            }
            @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                                                    NativeWebRequest w, WebDataBinderFactory b) { return jwt; }
        };

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(jwtResolver)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private LandlordProfileDto dto() {
        return LandlordProfileDto.builder()
                .id(UUID.randomUUID()).userId(userId).fullName("A")
                .identityNumber("0123456789").email("a@b.com").build();
    }

    @Test
    @DisplayName("GET /me returns profile from JWT subject")
    void getMe() throws Exception {
        when(service.getByUserId(userId)).thenReturn(dto());

        mvc.perform(get("/api/econtracts/landlord-profiles/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("A"));

        verify(service).getByUserId(userId);
    }

    @Test
    @DisplayName("GET /me returns 404 when profile missing")
    void getMeNotFound() throws Exception {
        when(service.getByUserId(userId)).thenThrow(new NotFoundException("missing"));

        mvc.perform(get("/api/econtracts/landlord-profiles/me"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /me upserts profile from JWT subject")
    void putMe() throws Exception {
        UpsertLandlordProfileRequest req = new UpsertLandlordProfileRequest(
                "A", "0123", "2020-01-01", "HN", "addr", "0900", "a@b.com", "bank");
        when(service.upsert(eq(userId), any())).thenReturn(dto());

        mvc.perform(put("/api/econtracts/landlord-profiles/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Profile updated successfully"));

        verify(service).upsert(eq(userId), any());
    }
}
