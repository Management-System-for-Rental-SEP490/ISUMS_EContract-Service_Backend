package com.isums.contractservice.infrastructures.grpcs;

import com.isums.contractservice.configurations.BearerTokenCallCredentials;
import com.isums.contractservice.configurations.ServiceAccountTokenProvider;
import com.isums.userservice.grpc.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserGrpcClient {

    private final UserServiceGrpc.UserServiceBlockingStub stub;
    private final ServiceAccountTokenProvider tokenProvider;

    public UserResponse getUserByEmail(String email, String jwtToken) {
        GetUserByEmailRequest req = GetUserByEmailRequest.newBuilder().setEmail(email).build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(jwtToken)).getUserByEmail(req);
    }

    public UserResponse getUserByEmail(String email) {
        String token = tokenProvider.getToken();
        GetUserByEmailRequest req = GetUserByEmailRequest.newBuilder().setEmail(email).build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(token)).getUserByEmail(req);
    }

    public GetUserRolesResponse getUserRoles(String keycloakId, String jwtToken) {
        GetUserRolesRequest req = GetUserRolesRequest.newBuilder().setKeycloakId(keycloakId).build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(jwtToken)).getUserRoles(req);
    }

    public UserResponse getUserById(String userId) {
        String token = tokenProvider.getToken();
        GetUserByIdRequest req = GetUserByIdRequest.newBuilder().setUserId(userId).build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(token)).getUserById(req);
    }

    public UserResponse getUserIdAndRoleByKeyCloakId(String keycloakId) {
        GetUserIdAndRoleByKeyCloakIdRequest req = GetUserIdAndRoleByKeyCloakIdRequest.newBuilder().setKeycloakId(keycloakId).build();
        return stub.getUserIdAndRoleByKeyCloakId(req);
    }

    /**
     * Write-back profile metadata to user-service so subsequent contracts for
     * the same tenant auto-fill. Proto convention: "" = don't touch. Caller
     * passes only the fields relevant to the contract's tenant type (VN
     * block for Vietnamese, passport block for foreigners — the other block
     * should be all-blank).
     * <p>
     * Uses service-account token so the call works outside of an incoming
     * MANAGER JWT context (e.g. called from @TransactionalEventListener
     * afterCommit where the request thread-local is gone).
     */
    public UserResponse updateUserProfile(UpdateUserProfileData data) {
        String token = tokenProvider.getToken();
        UpdateUserProfileRequest req = UpdateUserProfileRequest.newBuilder()
                .setUserId(data.userId())
                // VN block
                .setDateOfIssue(nz(data.dateOfIssueIso()))
                .setPlaceOfIssue(nz(data.placeOfIssue()))
                .setPermanentAddress(nz(data.permanentAddress()))
                // Shared
                .setDateOfBirth(nz(data.dateOfBirthIso()))
                .setGender(nz(data.gender()))
                // Foreign block
                .setPassportNumber(nz(data.passportNumber()))
                .setPassportIssueDate(nz(data.passportIssueDateIso()))
                .setPassportExpiryDate(nz(data.passportExpiryDateIso()))
                .setNationality(nz(data.nationality()))
                .setVisaType(nz(data.visaType()))
                .setVisaExpiryDate(nz(data.visaExpiryDateIso()))
                .build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(token)).updateUserProfile(req);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Parameter object for updateUserProfile. Record keeps call sites
     * explicit (callers can't mix up positional args of 12 strings).
     * All fields are optional; pass null for "don't touch".
     */
    public record UpdateUserProfileData(
            String userId,
            // VN block
            String dateOfIssueIso,
            String placeOfIssue,
            String permanentAddress,
            // Shared
            String dateOfBirthIso,
            String gender,
            // Foreign block
            String passportNumber,
            String passportIssueDateIso,
            String passportExpiryDateIso,
            String nationality,
            String visaType,
            String visaExpiryDateIso
    ) {}
}
