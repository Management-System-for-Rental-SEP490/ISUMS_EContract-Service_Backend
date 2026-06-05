package com.isums.contractservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.assetservice.grpc.AssetItemDto;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.domains.entities.*;
import com.isums.contractservice.domains.enums.DepositHandling;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.domains.enums.RelocationFaultParty;
import com.isums.contractservice.domains.enums.RelocationRequestKind;
import com.isums.contractservice.domains.enums.RelocationRequestStatus;
import com.isums.contractservice.domains.events.*;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.exceptions.OcrValidationException;
import com.isums.contractservice.infrastructures.abstracts.*;
import com.isums.contractservice.infrastructures.grpcs.*;
import com.isums.contractservice.infrastructures.mappers.EContractMapper;
import com.isums.contractservice.infrastructures.repositories.*;
import com.isums.contractservice.utils.NumberToTextConverter;
import com.isums.houseservice.grpc.HouseResponse;
import com.isums.userservice.grpc.UserResponse;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import common.paginations.cache.CachedPageService;
import common.paginations.converters.SpringPageConverter;
import common.paginations.dtos.PageRequest;
import common.paginations.dtos.PageResponse;
import common.paginations.specifications.SpecificationBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.type.TypeReference;

import java.io.*;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EContractServiceImpl implements EContractService {

    private final VnptEContractClient vnptClient;
    private final KafkaTemplate<String, Object> kafka;
    private final SimpMessagingTemplate ws;
    private final HouseGrpcClient houseGrpc;
    private final UserGrpcClient userGrpc;
    private final AssetGrpcClient assetGrpc;
    private final EContractRepository contractRepo;
    private final EContractTemplateRepository templateRepo;
    private final LandlordProfileRepository landlordRepo;
    private final EContractMapper mapper;
    private final S3Service s3;
    private final ObjectMapper json;
    private final ContractTokenService contractTokenService;
    private final RenewalRequestRepository renewalRequestRepo;
    private final RenewalServiceImpl renewalService;
    private final ContractCoTenantRepository coTenantRepo;
    private final ContractRelocationRequestRepository relocationRequestRepo;
    private final ContractHtmlBuilder htmlBuilder;
    private final OutboxPublisher outboxPublisher;

    private static final String PAGE_NS = "econtracts";
    private static final Duration PAGE_TTL = Duration.ofMinutes(60);

    private static final ThreadLocal<com.isums.contractservice.domains.dtos.ReplacementContext> replacementContextHolder = new ThreadLocal<>();

    public static void setPendingReplacementContext(com.isums.contractservice.domains.dtos.ReplacementContext ctx) {
        replacementContextHolder.set(ctx);
    }

    public static void clearPendingReplacementContext() {
        replacementContextHolder.remove();
    }

    private final CachedPageService cachedPageService;

    @Value("${ocr.service.url}")
    private String ocrUrl;

    @Value("${ocr.service.shared-secret:}")
    private String ocrSharedSecret;

    @Value("${vnpt.landlord.username}")
    private String vnptLandlordUsername;

    @Value("${app.contract.view-url:https://outsystem.isums.pro/contracts}")
    private String contractViewBaseUrl;

    @Value("${app.contract.pdf-url-ttl-minutes:30}")
    private int pdfUrlTtlMinutes;

    private final DateTimeFormatter DMY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);

    private record OcrResult(String identityNumber, String fullName, String dateOfBirth, String gender,
                             String placeOfOrigin, String address, String issueDate, String issuePlace) {
        static OcrResult from(JsonNode n) {
            return new OcrResult(t(n, "identityNumber"), t(n, "fullName"),
                    t(n, "dateOfBirth"), t(n, "gender"),
                    t(n, "placeOfOrigin"), t(n, "address"),
                    t(n, "issueDate"), t(n, "issuePlace"));
        }

        private static String t(JsonNode n, String f) {
            JsonNode v = n.path(f);
            return v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public EContractDto createDraft(UUID actorId, String jwtToken, CreateEContractRequest req) {
        try {

            if (!req.hasRequiredIdentity()) {
                throw new IllegalArgumentException(req.tenantTypeOrDefault() == com.isums.contractservice.domains.enums.TenantType.FOREIGNER
                        ? "Foreign tenants must have a passport number and nationality."
                        : "Vietnamese tenants must have a Citizen ID number.");
            }

            HouseResponse house = houseGrpc.getHouseById(req.houseId());
            if (house == null) throw new NotFoundException("House not found: " + req.houseId());

            UUID tenantId;
            if (!req.isNewAccount()) {
                tenantId = UUID.fromString(userGrpc.getUserByEmail(req.email(), jwtToken).getId());
            } else {
                Optional<UUID> existingTenantId = findExistingTenantIdByEmail(req.email(), jwtToken);
                if (existingTenantId.isPresent()) {
                    tenantId = existingTenantId.get();
                    log.warn("[Booking] isNewAccount=true but email already exists; using existing userId={} email={}",
                            tenantId, req.email());
                } else {
                    tenantId = UUID.randomUUID();
                createVnptUser(vnptClient.getToken(), tenantId, req);
                    CreateUserPlacedEvent event = CreateUserPlacedEvent.builder()
                            .id(tenantId)
                            .name(req.name())
                            .email(req.email())
                            .phoneNumber(req.phoneNumber())
                            .identityNumber(req.identityNumber())
                            .dateOfIssue(req.dateOfIssue() != null ? req.dateOfIssue().toString() : null)
                            .placeOfIssue(req.placeOfIssue())
                            .permanentAddress(req.permanentAddress())
                            .dateOfBirth(req.dateOfBirth() != null ? req.dateOfBirth().toString() : null)
                            .gender(req.gender())
                            .passportNumber(req.passportNumber())
                            .passportIssueDate(req.passportIssueDate() != null ? req.passportIssueDate().toString() : null)
                            .passportExpiryDate(req.passportExpiryDate() != null ? req.passportExpiryDate().toString() : null)
                            .nationality(req.nationality())
                            .visaType(req.visaType())
                            .visaExpiryDate(req.visaExpiryDate() != null ? req.visaExpiryDate().toString() : null)
                            .language(resolveUserLanguage(req))
                            .isEnabled(false)
                            .build();
                    outboxPublisher.enqueue("createUser-topic", tenantId.toString(), event, UUID.randomUUID().toString());
                }
            }

            cachedPageService.evictAll(PAGE_NS);

            EContract saved = buildAndSaveDraft(actorId, tenantId, req, house);
            log.info("[Booking] Lock acquired contractId={} houseId={} tenantId={} actorId={}",
                    saved.getId(), saved.getHouseId(), saved.getUserId(), actorId);
            return mapper.contractToDto(saved);

        } catch (IllegalArgumentException | IllegalStateException | NotFoundException e) {
            throw e;
        } catch (Exception ex) {
            log.error("createDraft failed", ex);
            throw new IllegalStateException("Failed to create contract: " + ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EContractDto getById(UUID id, org.springframework.security.core.Authentication auth) {
        EContract contract = findById(id);
        requireAccess(contract, auth);

        if (contract.getStatus() != EContractStatus.DRAFT && contract.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {

            String pdfUrl = getPdfPresignedUrlForAdmin(id);

            EContractDto dto = mapper.contractToDto(contract);
            return dto.updatePdfUrl(pdfUrl);
        }
        return mapper.contractToDto(contract);
    }

    @Override
    public PageResponse<EContractDto> getAll(PageRequest request, org.springframework.security.core.Authentication auth) {
        ContractScope scope = resolveScope(auth);

        String scopedNs = PAGE_NS + ":" + scope.cacheKey();
        return cachedPageService.getOrLoad(scopedNs, request, new TypeReference<>() {
                },
                () -> loadPage(request, scope)
        );
    }

    @Override
    @Transactional
    @CacheEvict(value = "user-contracts", allEntries = true)
    public EContractDto updateContract(UUID id, UpdateEContractRequest req) {
        EContract c = findById(id);
        EContractStatus current = c.getStatus();

        if (current == EContractStatus.PENDING_TENANT_REVIEW) {
            c.getStatus().validateTransition(EContractStatus.CORRECTING);
            mapper.patch(req, c);
            c.setStatus(EContractStatus.CORRECTING);

            s3.deleteIfExists(c.getSnapshotKey());
            c.setSnapshotKey(null);

            contractRepo.save(c);

            sendWsStatus(c.getId(), "CORRECTING", "The contract is being revised by the landlord. Please wait for the new version.");

            log.info("[EContract] CORRECTING contractId={}", id);

            cachedPageService.evictAll(PAGE_NS);

        } else if (current == EContractStatus.DRAFT || current == EContractStatus.CORRECTING) {
            mapper.patch(req, c);
            contractRepo.save(c);

        } else {
            throw new IllegalStateException("Cannot edit a contract in status: " + current);
        }

        return mapper.contractToDto(c);
    }

    @Override
    @Transactional
    @CacheEvict(value = "user-contracts", allEntries = true)
    public EContractDto confirmByAdmin(UUID contractId, UUID actorId) {
        EContract c = findById(contractId);
        EContractStatus cur = c.getStatus();

        if (cur != EContractStatus.DRAFT && cur != EContractStatus.CORRECTING && cur != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Confirmation is only allowed in DRAFT, PENDING_TENANT_REVIEW or CORRECTING. Current: " + cur);
        }

        byte[] pdfBytes = renderHtmlToPdf(c.getHtml());

        s3.deleteIfExists(c.getSnapshotKey());
        String snapshotKey = s3.uploadContractPdf(pdfBytes, contractId);

        c.setSnapshotKey(snapshotKey);
        c.getStatus().validateTransition(EContractStatus.PENDING_TENANT_REVIEW);
        c.setStatus(EContractStatus.PENDING_TENANT_REVIEW);

        if (c.getTenantEmail() == null || c.getTenantEmail().isBlank()) {
            String resolvedEmail = resolveTenantEmail(c.getUserId());
            if (resolvedEmail != null && !resolvedEmail.isBlank()) {
                c.setTenantEmail(resolvedEmail);
            }
        }

        contractRepo.save(c);

        String magicToken = contractTokenService.generateToken(contractId, c.getUserId());
        String pdfViewUrl = s3.presignedUrl(snapshotKey, 24 * 60);
        String confirmUrl = contractViewBaseUrl + "/" + contractId + "/confirm?token=" + magicToken;

        ConfirmAndSendToTenantEvent event = ConfirmAndSendToTenantEvent.builder()
                .messageId(UUID.randomUUID().toString())
                .recipientUserId(c.getUserId())
                .recipientEmail(c.getTenantEmail())
                .recipientName(c.getTenantName())
                .contractId(contractId)
                .contractName(c.getName())
                .url(pdfViewUrl)
                .confirmUrl(confirmUrl)
                .startDate(c.getStartAt())
                .endDate(c.getEndAt())
                .contractLanguage(c.getContractLanguage() != null ? c.getContractLanguage().name() : null)
                .build();

        cachedPageService.evictAll(PAGE_NS);

        outboxPublisher.enqueue(
                "confirmAndSendToTenant-topic",
                contractId.toString(),
                event,
                event.messageId());

        log.info("[EContract] PENDING_TENANT_REVIEW contractId={} snapshotKey={} outbox enqueued",
                contractId, snapshotKey);
        return mapper.contractToDto(c);
    }

    @Transactional(readOnly = true)
    public String getPdfPresignedUrlForAdmin(UUID contractId) {
        EContract c = findById(contractId);

        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("The contract PDF has not been generated yet.");
        }

        return s3.presignedUrl(c.getSnapshotKey(), 60);
    }

    @Override
    @Transactional(readOnly = true)
    public String getPdfPresignedUrl(UUID contractId, String contractToken) {
        contractTokenService.validateToken(contractToken, contractId);
        EContract c = findById(contractId);

        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("The contract PDF has not been generated yet.");
        }

        return s3.presignedUrl(c.getSnapshotKey(), pdfUrlTtlMinutes);
    }

    @Override
    @Transactional
    public VnptDocumentDto tenantConfirmWithCccd(UUID contractId, MultipartFile frontImage, MultipartFile backImage, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);

        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Contract is not awaiting confirmation. Current: " + c.getStatus());
        }
        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("The contract has no PDF snapshot. Please contact the landlord.");
        }

        validateImage(frontImage, "front");
        validateImage(backImage, "back");

        sendCccdProgress(contractId, "OCR_START");

        OcrResult ocrFront = callOcrCccdQuickVerify(
                frontImage, backImage, c.getCccdNumber(), c.getTenantName());

        sendCccdProgress(contractId, "PDF_ASSEMBLY");

        s3.deleteIfExists(c.getCccdFrontKey());
        s3.deleteIfExists(c.getCccdBackKey());
        String frontKey = s3.uploadCccdImage(frontImage, contractId, "mat-truoc");
        String backKey = s3.uploadCccdImage(backImage, contractId, "mat-sau");

        byte[] snapshotPdf = s3.downloadBytes(c.getSnapshotKey());
        byte[] cccdFront = s3.compressCccdImage(s3.downloadBytes(frontKey));
        byte[] cccdBack = s3.compressCccdImage(s3.downloadBytes(backKey));
        byte[] finalPdf = s3.appendCccdPage(snapshotPdf, cccdFront, cccdBack);

        try (PDDocument doc = Loader.loadPDF(finalPdf)) {
            log.info("[EContract] finalPdf pages={}", doc.getNumberOfPages());
        } catch (IOException _) {
        }

        Map<String, AnchorBoxVnpt> anchors = findAnchors(finalPdf, List.of("SIGN_A", "SIGN_B"));
        VnptPosition posA = getVnptPosition(
                finalPdf, anchors.get("SIGN_A"), 170, 90, 60, 18, -28, 35, 20, 60);
        VnptPosition posB = getVnptPosition(
                finalPdf, anchors.get("SIGN_B"), 170, 90, 60, 18, 0, 35, 20, 60);

        log.info("[EContract] finalPdf size={}KB contractId={}", finalPdf.length / 1024, contractId);

        sendCccdProgress(contractId, "VNPT_UPLOAD");

        String token = vnptClient.getToken();
        VnptDocumentDto document = ensureVnptDocument(c, finalPdf, token);
        String documentId = document.id();

        s3.deleteIfExists(frontKey);
        s3.deleteIfExists(backKey);

        sendCccdProgress(contractId, "FINALIZING");

        c.setDocumentId(documentId);
        c.setDocumentNo(document.no());
        c.setCccdFrontKey(null);
        c.setCccdBackKey(null);
        c.setCccdVerifiedAt(Instant.now());
        if (c.getStatus() != EContractStatus.READY) {
            c.getStatus().validateTransition(EContractStatus.READY);
            c.setStatus(EContractStatus.READY);
        }
        contractRepo.save(c);

        updateProcess(token, documentId,
                vnptLandlordUsername,
                c.getUserId().toString(),
                posA.pos(),
                posB.pos(),
                posA.pageSign(),
                posB.pageSign());

        VnptResult<VnptDocumentDto> sendResult = vnptClient.sendProcess(token, documentId);
        if (sendResult == null || sendResult.getData() == null) {
            c.setStatus(EContractStatus.PENDING_TENANT_REVIEW);
            contractRepo.save(c);
            sendCccdProgress(contractId, "FAILED");
            throw new IllegalStateException("VNPT sendProcess failed: "
                    + (sendResult != null ? sendResult.getMessage() : "null"));
        }

        log.info("[EContract] READY contractId={} documentId={} cccdPurged=true",
                contractId, documentId,
                ocrFront != null ? ocrFront.identityNumber() : "ocr-skipped");

        contractTokenService.invalidateToken(contractToken);

        cachedPageService.evictAll(PAGE_NS);
        sendReadyForLandlordSignatureEvent(c);

        sendCccdProgress(contractId, "COMPLETE");

        return sendResult.getData();
    }

    @Override
    @Transactional
    public VnptDocumentDto tenantConfirmWithPassport(UUID contractId, MultipartFile passportImage, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);

        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Contract is not awaiting confirmation. Current: " + c.getStatus());
        }
        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("The contract has no PDF snapshot. Please contact the landlord.");
        }

        validateImage(passportImage, "passport");

        PassportIdentityDto ocrPassport = callOcrPassportAndValidate(
                passportImage, c.getPassportNumber(), c.getTenantName(), c.getNationality());

        s3.deleteIfExists(c.getPassportFrontKey());
        String passportKey = s3.uploadPassportImage(passportImage, contractId);

        byte[] snapshotPdf = s3.downloadBytes(c.getSnapshotKey());
        byte[] passportBytes = s3.compressCccdImage(s3.downloadBytes(passportKey));
        byte[] finalPdf = s3.appendPassportPage(snapshotPdf, passportBytes);

        try (PDDocument doc = Loader.loadPDF(finalPdf)) {
            log.info("[EContract] finalPdf pages={} (passport flow)", doc.getNumberOfPages());
        } catch (IOException _) {
        }

        Map<String, AnchorBoxVnpt> anchors = findAnchors(finalPdf, List.of("SIGN_A", "SIGN_B"));
        VnptPosition posA = getVnptPosition(
                finalPdf, anchors.get("SIGN_A"), 170, 90, 60, 18, -28, 35, 20, 60);
        VnptPosition posB = getVnptPosition(
                finalPdf, anchors.get("SIGN_B"), 170, 90, 60, 18, 0, 35, 20, 60);

        log.info("[EContract] finalPdf size={}KB contractId={} (passport)", finalPdf.length / 1024, contractId);

        String token = vnptClient.getToken();
        VnptDocumentDto document = ensureVnptDocument(c, finalPdf, token);
        String documentId = document.id();

        s3.deleteIfExists(passportKey);

        c.setDocumentId(documentId);
        c.setDocumentNo(document.no());
        c.setPassportFrontKey(null);
        c.setPassportVerifiedAt(Instant.now());
        if (c.getStatus() != EContractStatus.READY) {
            c.getStatus().validateTransition(EContractStatus.READY);
            c.setStatus(EContractStatus.READY);
        }
        contractRepo.save(c);

        updateProcess(token, documentId,
                vnptLandlordUsername,
                c.getUserId().toString(),
                posA.pos(),
                posB.pos(),
                posA.pageSign(),
                posB.pageSign());

        VnptResult<VnptDocumentDto> sendResult = vnptClient.sendProcess(token, documentId);
        if (sendResult == null || sendResult.getData() == null) {
            c.setStatus(EContractStatus.PENDING_TENANT_REVIEW);
            contractRepo.save(c);
            throw new IllegalStateException("VNPT sendProcess failed: "
                    + (sendResult != null ? sendResult.getMessage() : "null"));
        }

        log.info("[EContract] READY contractId={} documentId={} passport={}",
                contractId, documentId,
                ocrPassport != null ? ocrPassport.getPassportNumber() : "ocr-skipped");

        contractTokenService.invalidateToken(contractToken);

        cachedPageService.evictAll(PAGE_NS);
        sendReadyForLandlordSignatureEvent(c);

        return sendResult.getData();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPassport(UUID contractId) {
        EContract c = findById(contractId);
        return c.getPassportVerifiedAt() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public TenantMetaDto getTenantMeta(UUID contractId, String contractToken) {
        contractTokenService.validateToken(contractToken, contractId);
        EContract c = findById(contractId);
        String tenantType = c.getTenantType() != null ? c.getTenantType().name() : null;
        String contractLanguage = c.getContractLanguage() != null ? c.getContractLanguage().name() : null;
        return new TenantMetaDto(tenantType, contractLanguage);
    }

    @Override
    @Transactional(readOnly = true)
    public void triggerReadyForLandlordSignatureNotification(UUID contractId) {
        EContract contract = findById(contractId);

        if (contract.getStatus() != EContractStatus.READY) {
            throw new IllegalStateException(
                    "Only allowed to replay notifications when the contract is READY. Current: " + contract.getStatus());
        }
        if (contract.getCreatedBy() == null) {
            throw new IllegalStateException(
                    "Contract is missing createdBy so the notification recipient cannot be determined.");
        }

        sendReadyForLandlordSignatureEvent(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public void resendTenantSignatureNotification(UUID contractId) {
        EContract contract = findById(contractId);

        if (contract.getStatus() != EContractStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "Resend is only allowed when the contract is IN_PROGRESS (waiting for tenant signature). Current: " + contract.getStatus());
        }
        if (contract.getDocumentId() == null || contract.getDocumentId().isBlank()) {
            throw new IllegalStateException("Contract has no VNPT documentId; nothing to resend.");
        }
        if (contract.getUserId() == null) {
            throw new IllegalStateException("Contract has no tenant userId; cannot rebuild workflow.");
        }

        String token = vnptClient.getToken();
        String documentId = contract.getDocumentId();

        VnptResult<VnptDocumentDto> docResult = vnptClient.getEContractById(documentId, token);
        if (docResult == null || !Boolean.TRUE.equals(docResult.getSuccess()) || docResult.getData() == null) {
            String reason = docResult != null ? docResult.getMessage() : "null response";
            log.error("[EContract] resend lookup failed contractId={} documentId={} reason={}",
                    contractId, documentId, reason);
            throw new IllegalStateException("VNPT getEContractById failed: " + reason);
        }

        List<VnptProcess> existing = docResult.getData().processes();
        if (existing == null || existing.size() < 2) {
            throw new IllegalStateException(
                    "VNPT workflow has fewer than 2 processes; cannot rebuild for resend.");
        }

        List<VnptProcess> sorted = existing.stream()
                .sorted(Comparator.comparingInt(VnptProcess::orderNo))
                .toList();
        VnptProcess landlordProcess = sorted.get(0);
        VnptProcess tenantProcess = sorted.get(1);

        List<ProcessesRequestDTO> processes = List.of(
                new ProcessesRequestDTO(1, vnptLandlordUsername, "E",
                        landlordProcess.position(), landlordProcess.pageSign()),
                new ProcessesRequestDTO(2, contract.getUserId().toString(), "E",
                        tenantProcess.position(), tenantProcess.pageSign())
        );

        VnptResult<VnptDocumentDto> updateResult = vnptClient.UpdateProcess(token,
                new VnptUpdateProcessDTO(documentId, true, processes));
        if (updateResult == null || !Boolean.TRUE.equals(updateResult.getSuccess())) {
            String reason = updateResult != null ? updateResult.getMessage() : "null response";
            log.error("[EContract] resend update-process failed contractId={} reason={}",
                    contractId, reason);
            throw new IllegalStateException("VNPT UpdateProcess failed: " + reason);
        }

        VnptResult<VnptDocumentDto> sendResult = vnptClient.sendProcess(token, documentId);
        if (sendResult == null || !Boolean.TRUE.equals(sendResult.getSuccess())) {
            String reason = sendResult != null ? sendResult.getMessage() : "null response";
            log.error("[EContract] resend send-process failed contractId={} reason={}",
                    contractId, reason);
            throw new IllegalStateException("VNPT sendProcess failed: " + reason);
        }

        log.info("[EContract] Resent tenant-signature email contractId={} documentId={}",
                contractId, documentId);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ProcessResponse signByLandlord(VnptProcessDto process) {
        VnptProcessDto withToken = process.withNormalizedPosition().withToken(vnptClient.getToken());
        VnptResult<ProcessResponse> result = vnptClient.signProcess(withToken);

        if (result == null || result.getData() == null) {
            throw new IllegalStateException("Landlord signing failed: "
                    + (result != null ? result.getMessage() : "null"));
        }

        if (result.getSuccess() && result.getData().id() != null) {
            EContract c = findByDocumentId(String.valueOf(result.getData().id()));
            c.getStatus().validateTransition(EContractStatus.IN_PROGRESS);
            c.setStatus(EContractStatus.IN_PROGRESS);
            contractRepo.save(c);
            log.info("[EContract] IN_PROGRESS (landlord signed) contractId={}", c.getId());
            updateSnapshotFromVnpt(c);
        }

        cachedPageService.evictAll(PAGE_NS);

        return result.getData();
    }

    @Override
    @Transactional
    public void resendContractCompletedEvent(UUID contractId, String overrideTenantEmail) {
        EContract contract = findById(contractId);

        if (contract.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Resend ContractCompleted is only allowed when contract is COMPLETED. Current: "
                            + contract.getStatus());
        }

        if (overrideTenantEmail != null && !overrideTenantEmail.isBlank()) {
            contract.setTenantEmail(overrideTenantEmail.trim());
            contractRepo.save(contract);
            log.info("[EContract] resendContractCompletedEvent overrode tenantEmail contractId={} email={}",
                    contractId, overrideTenantEmail);
        }

        String signedPdfUrl = null;
        if (contract.getSnapshotKey() != null && !contract.getSnapshotKey().isBlank()) {
            try {
                signedPdfUrl = s3.presignedUrl(contract.getSnapshotKey(), 7 * 24 * 60);
            } catch (Exception ex) {
                log.warn("[EContract] resendContractCompletedEvent: presign failed contractId={}: {}",
                        contractId, ex.getMessage());
            }
        }

        sendContractCompletedEvent(contract, signedPdfUrl);
        log.info("[EContract] Replayed ContractCompleted contractId={} tenantEmail={}",
                contractId, contract.getTenantEmail());
    }

    @Override
    public ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode) {
        try {
            ProcessLoginInfoDto result = parseProcessLogin(vnptClient.getAccessInfoByProcessCode(processCode));
            EContract contract = contractRepo.findByDocumentId(result.documentId())
                    .orElseThrow(() -> new BusinessException(BusinessException.CONTRACT_NOT_FOUND, "Contract not found"));
            if (contract.getSnapshotKey() == null) {
                throw new BusinessException(BusinessException.PDF_NOT_READY, "Contract PDF not ready");
            }

            String pdfUrl = s3.presignedUrl(contract.getSnapshotKey(), pdfUrlTtlMinutes);
            return result.updatePdfUrl(pdfUrl);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("getAccessInfoByProcessCode failed processCode={}", processCode, ex);
            String detail = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
            if (detail.contains("invalid process code")) {
                throw new BusinessException(BusinessException.INVALID_PROCESS_CODE, "Invalid signing link");
            }
            throw new BusinessException(BusinessException.SIGNING_INFO_UNAVAILABLE, "Cannot load signing info");
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ProcessResponse signByTenant(VnptProcessDto process) {
        VnptResult<ProcessResponse> result = vnptClient.signProcess(process.withNormalizedPosition());

        if (result == null || result.getData() == null) {
            throw new IllegalStateException("Tenant signing failed: "
                    + (result != null ? result.getMessage() : "null"));
        }

        if (result.getSuccess() && result.getData().id() != null) {
            EContract c = findByDocumentId(String.valueOf(result.getData().id()));
            c.getStatus().validateTransition(EContractStatus.COMPLETED);
            c.setStatus(EContractStatus.COMPLETED);
            c.setDepositStatus(resolveCompletedDepositStatus(c));
            contractRepo.save(c);
            log.info("[EContract] COMPLETED contractId={}", c.getId());

            fetchAndStoreSignedPdf(c);

            cachedPageService.evictAll(PAGE_NS);
        }

        return result.getData();
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public void cancelByTenant(UUID contractId, String reason, UUID tenantUserId, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW
                && c.getStatus() != EContractStatus.IN_PROGRESS
                && c.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException("Tenant may only cancel in PENDING_TENANT_REVIEW or IN_PROGRESS. Current: " + c.getStatus());
        }
        c.setStatus(EContractStatus.CANCELLED_BY_TENANT);
        c.setTerminatedAt(Instant.now());
        c.setTerminatedReason(reason);
        c.setTerminatedBy(c.getUserId());
        purgePiiObjects(c);
        contractRepo.save(c);
        log.info("[EContract] CANCELLED_BY_TENANT contractId={} PII purged", contractId);

        contractTokenService.invalidateToken(contractToken);
        sendContractCancelledByTenantEvent(c);

        cachedPageService.evictAll(PAGE_NS);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public void cancelByLandlord(UUID contractId, String reason, UUID actorId) {
        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.CORRECTING
                && c.getStatus() != EContractStatus.READY) {
            throw new IllegalStateException("Landlord may only cancel in CORRECTING or READY. Current: " + c.getStatus());
        }
        c.setStatus(EContractStatus.CANCELLED_BY_LANDLORD);
        c.setTerminatedAt(Instant.now());
        c.setTerminatedReason(reason);
        c.setTerminatedBy(actorId);
        purgePiiObjects(c);
        contractRepo.save(c);
        log.info("[EContract] CANCELLED_BY_LANDLORD contractId={} PII purged", contractId);

        cachedPageService.evictAll(PAGE_NS);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public void forceCancelExpiredDeposit(UUID contractId, UUID actorId) {
        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Contract must be COMPLETED to force-cancel expired deposit. Current: " + c.getStatus());
        }
        if (c.getDepositStatus() != com.isums.contractservice.domains.enums.DepositStatus.UNPAID) {
            throw new IllegalStateException(
                    "Deposit is " + c.getDepositStatus() + " — only UNPAID deposits may be force-cancelled.");
        }

        Instant now = Instant.now();
        c.getStatus().validateTransition(EContractStatus.CANCELLED_BY_LANDLORD);
        c.setStatus(EContractStatus.CANCELLED_BY_LANDLORD);
        c.setDepositDueAt(null);
        c.setTerminatedAt(now);
        c.setTerminatedBy(actorId);
        c.setTerminatedReason("Deposit not paid — landlord forced cancellation");
        contractRepo.save(c);

        String tenantEmail = null;
        String tenantName = null;
        try {
            com.isums.userservice.grpc.UserResponse u = userGrpc.getUserById(c.getUserId().toString());
            if (u != null) {
                tenantEmail = u.getEmail();
                tenantName = u.getName();
            }
        } catch (Exception e) {
            log.warn("[EContract] forceCancel tenant lookup failed userId={}: {}",
                    c.getUserId(), e.getMessage());
        }

        String contractNo = c.getDocumentNo() != null
                ? c.getDocumentNo()
                : c.getId().toString().substring(0, 8).toUpperCase();
        String messageId = UUID.randomUUID().toString();

        com.isums.contractservice.domains.events.ContractDepositExpiredEvent event =
                com.isums.contractservice.domains.events.ContractDepositExpiredEvent.builder()
                        .contractId(c.getId())
                        .tenantId(c.getUserId())
                        .houseId(c.getHouseId())
                        .landlordId(c.getCreatedBy())
                        .tenantEmail(tenantEmail)
                        .tenantName(tenantName)
                        .contractNo(contractNo)
                        .depositAmount(c.getDepositAmount())
                        .depositDueAt(null)
                        .expiredAt(now)
                        .messageId(messageId)
                        .build();

        outboxPublisher.enqueue(
                "contract.deposit-expired",
                c.getId().toString(),
                event,
                messageId);

        log.info("[EContract] FORCE-CANCELLED expired deposit contractId={} contractNo={} actorId={} tenant={}",
                c.getId(), contractNo, actorId, tenantEmail);

        cachedPageService.evictAll(PAGE_NS);
    }

    @Override
    @Transactional
    @CacheEvict(allEntries = true, value = "allEContracts")
    public void deleteContract(UUID contractId, UUID actorId) {
        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT contracts may be deleted. Current: " + c.getStatus());
        }

        purgePiiObjects(c);
        c.setDeletedAt(Instant.now());
        c.setDeletedBy(actorId);
        c.setStatus(EContractStatus.DELETED);
        contractRepo.save(c);
        log.info("[EContract] SOFT-DELETED contractId={} by={} (row retained for audit)", contractId, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCccd(UUID contractId) {
        EContract c = findById(contractId);
        return c.getCccdVerifiedAt() != null;
    }

    @Override
    public EContractDto getOutSystem(String processCode) {
        ProcessLoginInfoDto info = getAccessInfoByProcessCode(processCode);
        return mapper.contractToDto(
                contractRepo.findByDocumentNo(info.documentNo())
                        .orElseThrow(() -> new NotFoundException("EContract not found")));
    }

    @Override
    @Cacheable(cacheNames = "vnptDocumentById", key = "#documentId")
    public VnptDocumentDto getVnptDocumentById(String documentId) {
        String token = vnptClient.getToken();
        VnptResult<VnptDocumentDto> r = vnptClient.getEContractById(documentId, token);
        if (r == null || r.getData() == null || r.getData().id() == null)
            throw new NotFoundException("VNPT document not found: " + documentId);
        return r.getData();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "user-contracts", key = "#keycloakId")
    public List<TenantEContractDto> getMyContracts(UUID keycloakId) {
        UUID userId = resolveInternalTenantId(keycloakId);
        List<EContract> contracts = contractRepo.findByUserIdOrderByCreatedAtDesc(userId);
        java.util.Map<UUID, String> pdfUrlByContract = contracts.parallelStream()
                .collect(java.util.HashMap::new,
                        (map, c) -> {
                            String url = resolvePdfUrlForTenant(c);
                            if (url != null) map.put(c.getId(), url);
                        },
                        java.util.HashMap::putAll);
        return contracts.stream()
                .map(c -> new TenantEContractDto(
                        c.getId(),
                        c.getName(),
                        c.getHouseId(),
                        c.getStartAt(),
                        c.getEndAt(),
                        c.getStatus(),
                        pdfUrlByContract.get(c.getId()),
                        c.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String getPdfUrlForTenant(UUID contractId, UUID keycloakId) {
        EContract c = findById(contractId);

        UUID userId = resolveInternalTenantId(keycloakId);

        if (!c.getUserId().equals(userId)) {
            throw new AccessDeniedException("You are not authorized to view this contract..");
        }
        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("The contract is not yet available in PDF format. Please contact the landlord.");
        }
        return s3.presignedUrl(c.getSnapshotKey(), 30);
    }

    @Transactional
    public void confirmRefund(UUID contractId, ConfirmRefundRequest req) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new RuntimeException("Contract not found: " + contractId));

        contract.getStatus().validateTransition(EContractStatus.DEPOSIT_REFUND_PENDING);
        contract.setStatus(EContractStatus.DEPOSIT_REFUND_PENDING);
        contractRepo.save(contract);

        String tenantEmail = contract.getTenantEmail();
        try {
            UserResponse tenant = userGrpc.getUserById(contract.getUserId().toString());
            if (tenant != null && tenant.getEmail() != null && !tenant.getEmail().isBlank()) {
                tenantEmail = tenant.getEmail();
            }
        } catch (Exception e) {
            log.warn("[Contract] Cannot resolve tenant email via gRPC, using contract fallback contractId={} tenantId={}: {}",
                    contractId, contract.getUserId(), e.getMessage());
        }

        kafka.send("contract.deposit-refund.confirmed",
                contractId.toString(),
                DepositRefundConfirmedEvent.builder()
                        .contractId(contractId)
                        .houseId(contract.getHouseId())
                        .tenantId(contract.getUserId())
                        .tenantEmail(tenantEmail)
                        .refundAmount(req.refundAmount())
                        .note(req.note())
                        .messageId(UUID.randomUUID().toString())
                        .build());

        log.info("[Contract] DEPOSIT_REFUND_PENDING contractId={} refundAmount={}",
                contractId, req.refundAmount());
    }

    @Transactional
    public EContractDto cloneForRenewal(UUID oldContractId, CloneForRenewalRequest req, UUID actorId, String jwtToken) {
        EContract old = findById(oldContractId);

        UserResponse tenant = userGrpc.getUserById(old.getUserId().toString());

        CreateEContractRequest newReq = CreateEContractRequest.builder()
                .isNewAccount(false)
                .name(old.getTenantName())
                .email(tenant.getEmail())
                .houseId(old.getHouseId())
                .startDate(req.newStartDate())
                .endDate(req.newEndDate())
                .rentAmount(req.newRentAmount())
                .payDate(old.getPayDate())
                .depositAmount(old.getDepositAmount())
                .depositDate(req.newStartDate())
                .handoverDate(req.newStartDate())
                .lateDays(old.getLateDays())
                .latePenaltyPercent(old.getLatePenaltyPercent())
                .depositRefundDays(old.getDepositRefundDays())
                .renewNoticeDays(old.getRenewNoticeDays() != null
                        ? old.getRenewNoticeDays() : 30)
                .build();

        EContractDto newContract = createDraft(actorId, jwtToken, newReq);

        if (req.renewalRequestId() != null) {
            renewalService.markNewContractDrafted(req.renewalRequestId(), newContract.id());
        }

        return newContract;
    }

    private void sendWsStatus(UUID contractId, String status, String message) {
        try {
            Map<String, Object> payload = Map.of(
                    "contractId", contractId.toString(),
                    "status", status,
                    "message", message);
            ws.convertAndSend("/topic/contract/" + contractId + "/status", (Object) payload);
        } catch (Exception e) {
            log.warn("[WS] Send failed contractId={}: {}", contractId, e.getMessage());
        }
    }

    private void sendCccdProgress(UUID contractId, String stage) {
        try {
            Map<String, Object> payload = Map.of(
                    "contractId", contractId.toString(),
                    "kind", "CCCD_PROGRESS",
                    "stage", stage,
                    "ts", System.currentTimeMillis());
            ws.convertAndSend("/topic/contract/" + contractId + "/status", (Object) payload);
        } catch (Exception e) {
            log.warn("[WS] CCCD progress send failed contractId={} stage={}: {}",
                    contractId, stage, e.getMessage());
        }
    }

    private OcrResult callOcrFrontAndValidate(MultipartFile file, String expectedId, String expectedName) {
        try {
            JsonNode node = callOcr(file, "/ocr/cccd");
            OcrResult result = OcrResult.from(node);

            if (!node.path("isFrontSide").asBoolean(false))
                throw new OcrValidationException(OcrValidationException.NOT_FRONT_SIDE, "Image is not the front of the Citizen ID.");

            if (result.identityNumber() == null)
                throw new OcrValidationException(OcrValidationException.CANNOT_READ_ID, "Could not read the Citizen ID number.");

            if (expectedId != null && !expectedId.isBlank()
                    && !result.identityNumber().equals(expectedId))
                throw new OcrValidationException(OcrValidationException.ID_MISMATCH, "Citizen ID number does not match the contract.");

            if (result.fullName() != null && expectedName != null && !expectedName.isBlank()) {
                String normOcr = norm(result.fullName());
                String normExpected = norm(expectedName);
                if (!normOcr.equals(normExpected) && !normExpected.contains(normOcr) && !normOcr.contains(normExpected)) {
                    throw new OcrValidationException(OcrValidationException.NAME_MISMATCH, "Name does not match the contract.");
                }
            } else if (result.fullName() == null) {
                log.warn("[OCR] Could not read name, skipping name check. id={}", result.identityNumber());
            }

            log.info("[OCR] Front OK id={} name={}", result.identityNumber(), result.fullName());
            return result;
        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Front service unavailable contractId-upload: {}", e.getMessage());
            throw new OcrValidationException(
                    OcrValidationException.OCR_SERVICE_UNAVAILABLE,
                    "Citizen ID OCR service is unavailable. Please try again later.");
        }
    }

    private OcrResult callOcrCccdQuickVerify(
            MultipartFile frontImage,
            MultipartFile backImage,
            String expectedId,
            String expectedName) {
        try {
            JsonNode node = callCccdQuickVerify(frontImage, backImage);
            JsonNode front = node.path("front");
            JsonNode back = node.path("back");

            if (!front.path("sideOk").asBoolean(false)) {
                throw new OcrValidationException(
                        OcrValidationException.NOT_FRONT_SIDE,
                        "Image is not the front of the Citizen ID.");
            }

            if (!back.path("sideOk").asBoolean(false)) {
                throw new OcrValidationException(
                        OcrValidationException.NOT_BACK_SIDE,
                        "Image is not the back of the Citizen ID.");
            }

            String identityNumber = normalizeIdentityNumber(tx(front, "identityNumber"));
            if (identityNumber == null) {
                throw new OcrValidationException(
                        OcrValidationException.CANNOT_READ_ID,
                        "Could not read the Citizen ID number on the front image.");
            }

            if (expectedId != null && !expectedId.isBlank()
                    && !identityNumber.equals(normalizeIdentityNumber(expectedId))) {
                throw new OcrValidationException(
                        OcrValidationException.ID_MISMATCH,
                        "Citizen ID number does not match the contract.");
            }

            String fullName = tx(front, "fullName");
            if (fullName == null) {
                throw new OcrValidationException(
                        OcrValidationException.CANNOT_READ_NAME,
                        "Could not read the tenant name on the front Citizen ID image.");
            }

            if (expectedName != null && !expectedName.isBlank()) {
                String normOcr = norm(fullName);
                String normExpected = norm(expectedName);
                if (!normOcr.equals(normExpected)
                        && !normExpected.contains(normOcr)
                        && !normOcr.contains(normExpected)) {
                    throw new OcrValidationException(
                            OcrValidationException.NAME_MISMATCH,
                            "Name does not match the contract.");
                }
            }

            log.info("[OCR] CCCD quick verify OK id={} name={} totalMs={}",
                    identityNumber, fullName, node.path("totalMs").asText("-"));
            return new OcrResult(identityNumber, fullName, null, null, null, null, null, null);
        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] CCCD quick verify unavailable: {}", e.getMessage());
            throw new OcrValidationException(
                    OcrValidationException.OCR_SERVICE_UNAVAILABLE,
                    "Citizen ID OCR service is unavailable. Please try again later.");
        }
    }

    private void validateCccdBack(MultipartFile file) {
        try {
            JsonNode node = callOcr(file, "/ocr/cccd/back");

            if (!node.path("isReadable").asBoolean(true))
                throw new OcrValidationException(OcrValidationException.IMAGE_NOT_READABLE, "Back-side image is not clear.");

            if (!node.path("isBackSide").asBoolean(false))
                throw new OcrValidationException(OcrValidationException.NOT_BACK_SIDE, "Image is not the back of the Citizen ID.");

        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Back service unavailable contractId-upload: {}", e.getMessage());
            throw new OcrValidationException(
                    OcrValidationException.OCR_SERVICE_UNAVAILABLE,
                    "Citizen ID OCR service is unavailable. Please try again later.");
        }
    }

    private PassportIdentityDto callOcrPassportAndValidate(
            MultipartFile file, String expectedPassportNumber, String expectedName, String expectedNationality) {
        try {
            JsonNode node = callOcr(file, "/ocr/passport");
            PassportIdentityDto result = json.treeToValue(node, PassportIdentityDto.class);

            if (result == null || result.getPassportNumber() == null)
                throw new OcrValidationException(OcrValidationException.CANNOT_READ_PASSPORT,
                        "Could not read passport number from MRZ. Please capture the info page clearly.");

            if (expectedPassportNumber != null && !expectedPassportNumber.isBlank()
                    && !result.getPassportNumber().equalsIgnoreCase(expectedPassportNumber)) {
                throw new OcrValidationException(OcrValidationException.PASSPORT_MISMATCH,
                        "Passport number does not match the contract.");
            }

            if (result.getExpiryDate() != null && !result.getExpiryDate().isBlank()) {
                try {
                    java.time.LocalDate expiry = java.time.LocalDate.parse(result.getExpiryDate());
                    if (expiry.isBefore(java.time.LocalDate.now())) {
                        throw new OcrValidationException(OcrValidationException.PASSPORT_EXPIRED,
                                "Passport expired on " + expiry + ".");
                    }
                } catch (java.time.format.DateTimeParseException ignored) {
                }
            }

            String ocrName = result.getFullName();
            if (ocrName == null) {
                ocrName = joinName(result.getGivenName(), result.getSurname());
            }
            if (ocrName != null && expectedName != null && !expectedName.isBlank()) {
                String normOcr = norm(ocrName);
                String normExpected = norm(expectedName);
                if (!normOcr.equals(normExpected) && !normExpected.contains(normOcr) && !normOcr.contains(normExpected)) {
                    throw new OcrValidationException(OcrValidationException.NAME_MISMATCH,
                            "Name on passport does not match the contract.");
                }
            }

            if (expectedNationality != null && !expectedNationality.isBlank()) {
                String ocrNat = result.getNationality();
                String ocrCode = result.getCountryCode();
                boolean natMatch = ocrNat != null && norm(ocrNat).equals(norm(expectedNationality));
                boolean codeMatch = ocrCode != null && ocrCode.equalsIgnoreCase(expectedNationality);
                if (!natMatch && !codeMatch) {
                    log.warn("[OCR] Nationality mismatch expected={} ocr={} code={}",
                            expectedNationality, ocrNat, ocrCode);
                }
            }

            log.info("[OCR] Passport OK number={} name={} nationality={}",
                    result.getPassportNumber(), ocrName, result.getNationality());
            return result;
        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Passport service unavailable contractId-upload: {}", e.getMessage());
            throw new OcrValidationException(
                    OcrValidationException.OCR_SERVICE_UNAVAILABLE,
                    "Passport OCR service is unavailable. Please try again later.");
        }
    }

    private String joinName(String given, String surname) {
        if (given == null && surname == null) return null;
        if (given == null) return surname;
        if (surname == null) return given;
        return (surname + " " + given).trim();
    }

    private JsonNode callCccdQuickVerify(MultipartFile frontImage, MultipartFile backImage) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("front", multipartResource(frontImage));
        body.add("back", multipartResource(backImage));
        var spec = RestClient.create().post()
                .uri(ocrUrl + "/ocr/cccd/verify")
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (ocrSharedSecret != null && !ocrSharedSecret.isBlank()) {
            spec = spec.header("X-OCR-Secret", ocrSharedSecret);
        }
        String raw = spec.body(body).retrieve().body(String.class);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("OCR service returned an empty response");
        }
        return json.readTree(raw);
    }

    private JsonNode callOcr(MultipartFile file, String path) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", multipartResource(file));
        var spec = RestClient.create().post()
                .uri(ocrUrl + path)
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (ocrSharedSecret != null && !ocrSharedSecret.isBlank()) {
            spec = spec.header("X-OCR-Secret", ocrSharedSecret);
        }
        String raw = spec.body(body).retrieve().body(String.class);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("OCR service returned an empty response");
        }
        return json.readTree(raw);
    }

    private ByteArrayResource multipartResource(MultipartFile file) throws IOException {
        return new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
    }

    private String normalizeIdentityNumber(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private void validateImage(MultipartFile f, String side) {
        if (f == null || f.isEmpty())
            throw new IllegalArgumentException("Image " + side + " must not be blank.");
        if (f.getContentType() == null || !f.getContentType().startsWith("image/"))
            throw new IllegalArgumentException("File " + side + " must be an image.");
        if (f.getSize() < 50 * 1024)
            throw new IllegalArgumentException("Image " + side + " is too small (< 50KB).");
        if (f.getSize() > 10 * 1024 * 1024)
            throw new IllegalArgumentException("Image " + side + " is too large (> 10MB).");
    }

    private String norm(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase()
                .replace("đ", "d")
                .replace("ư", "u")
                .replace("ơ", "o");
        String nfd = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String slugifyName(String s) {
        if (s == null || s.isBlank()) return "Unknown";
        String replaced = s.trim()
                .replace("đ", "d").replace("Đ", "D")
                .replace("ư", "u").replace("Ư", "U")
                .replace("ơ", "o").replace("Ơ", "O");
        String nfd = Normalizer.normalize(replaced, Normalizer.Form.NFD);
        String ascii = nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String slug = ascii.replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "Unknown" : slug;
    }

    private void updateProcess(String token, String documentId, String userFirst, String userSecond,
                               String posA, String posB, int pageA, int pageB) {
        vnptClient.UpdateProcess(token, new VnptUpdateProcessDTO(documentId, true, List.of(
                new ProcessesRequestDTO(1, userFirst, "E", posA, pageA),
                new ProcessesRequestDTO(2, userSecond, "E", posB, pageB)
        )));
    }

    private void createVnptUser(String token, UUID id, CreateEContractRequest req) {
        VnptResult<List<VnptUserDto>> r = vnptClient.CreateOrUpdateUser(token,
                new VnptUserUpsert(id.toString(), req.email(), req.name(), req.email(),
                        req.phoneNumber(), 1, 0, 2, true, true, 1, -1,
                        new ArrayList<>(List.of(3110)),
                        new ArrayList<>(List.of(UUID.fromString("0aa2afc9-39c5-4652-baec-08ddc28cdda2")))));
        if (r == null || r.getData() == null)
            throw new IllegalStateException("Failed to create VNPT user: "
                    + (r != null ? r.getMessage() : "null"));
    }

    private EContract buildAndSaveDraft(UUID actorId, UUID tenantId,
                                        CreateEContractRequest req, HouseResponse house) {
        LandlordProfile lp = landlordRepo.findByUserId(actorId)
                .orElseThrow(() -> new IllegalStateException(
                        "Landlord profile not yet updated. " + "Please update via PUT /api/econtracts/landlord-profiles/me."));

        Map<String, Object> meters = req.meterReadingsStart() != null
                ? req.meterReadingsStart().asMap() : Map.of();

        long draftTimestamp = System.currentTimeMillis();

        String draftDocumentNo = "EC_" + draftTimestamp;
        ContractHtmlBuilder.BuildInput input = new ContractHtmlBuilder.BuildInput(
                req, lp, house.getAddress(),
                house.getAreaM2(),
                house.getStructure(),
                house.getLandCertNumber(),
                house.getLandCertIssueDate(),
                house.getLandCertIssuer(),
                buildAssetTable(req.houseId()),
                "",
                "",
                "",
                meters, req.contractLanguageOrDefault(), null,
                replacementContextHolder.get(),
                draftDocumentNo);

        String html = htmlBuilder.build(input);

        UUID regionId = null;
        try {
            if (house.getRegionId() != null && !house.getRegionId().isBlank()) {
                regionId = UUID.fromString(house.getRegionId());
            }
        } catch (IllegalArgumentException ex) {
            log.warn("[EContract] Invalid regionId from house gRPC: '{}' — contract will be region-unscoped",
                    house.getRegionId());
        }

        EContract e = EContract.builder()
                .userId(tenantId).houseId(req.houseId())
                .regionId(regionId)
                .startAt(req.startDate()).endAt(req.endDate())
                .name("EContract_" + slugifyName(req.name()) + "_" + draftTimestamp)
                .documentNo("EC_" + draftTimestamp)
                .html(html)
                .status(EContractStatus.DRAFT)
                .rentAmount(req.rentAmount()).depositAmount(req.depositAmount())
                .depositStatus(req.depositAmount() != null && req.depositAmount() > 0
                        ? DepositStatus.UNPAID
                        : DepositStatus.PAID)
                .transferredDepositAmount(0L)
                .payDate(req.payDate()).lateDays(req.lateDaysOrDefault())
                .latePenaltyPercent(req.latePenaltyPercentOrDefault())
                .depositRefundDays(req.depositRefundDaysOrDefault())
                .renewNoticeDays(req.renewNoticeDaysOrDefault())
                .handoverDate(req.effectiveHandoverDate())
                .cccdNumber(req.identityNumber())
                .tenantType(req.tenantTypeOrDefault())
                .contractLanguage(req.contractLanguageOrDefault())
                .passportNumber(req.passportNumber())
                .passportIssueDate(req.passportIssueDate())
                .passportIssuePlace(req.passportIssuePlace())
                .passportExpiryDate(req.passportExpiryDate())
                .visaType(req.visaType())
                .visaExpiryDate(req.visaExpiryDate())
                .nationality(req.nationality())
                .dateOfBirth(req.dateOfBirth())
                .gender(req.gender())
                .occupation(req.occupation())
                .permanentAddress(req.permanentAddress())
                .detailedAddress(req.detailedAddress() != null ? req.detailedAddress().asMap() : null)
                .petPolicy(req.petPolicyOrDefault())
                .smokingPolicy(req.smokingPolicyOrDefault())
                .subleasePolicy(req.subleasePolicyOrDefault())
                .visitorPolicy(req.visitorPolicyOrDefault())
                .tempResidenceRegisterBy(req.tempResidenceRegisterByOrDefault())
                .taxResponsibility(req.taxResponsibilityOrDefault())
                .meterReadingsStart(meters.isEmpty() ? null : new HashMap<>(meters))
                .hasPowerCutClause(req.hasPowerCutClause() != null ? req.hasPowerCutClause() : false)
                .tenantName(req.name())
                .tenantEmail(req.email())
                .createdBy(actorId).build();
        contractRepo.save(e);
        log.info("[EContract] Created DRAFT contractId={} language={} tenantType={}",
                e.getId(), e.getContractLanguage(), e.getTenantType());

        if (req.coTenants() != null && !req.coTenants().isEmpty()) {
            List<ContractCoTenant> coTenants = req.coTenants().stream()
                    .map(dto -> ContractCoTenant.builder()
                            .contractId(e.getId())
                            .fullName(dto.fullName())
                            .identityNumber(dto.identityNumber())
                            .identityType(dto.identityType())
                            .dateOfBirth(dto.dateOfBirth())
                            .gender(dto.gender())
                            .nationality(dto.nationality())
                            .relationship(dto.relationship())
                            .phoneNumber(dto.phoneNumber())
                            .build())
                    .toList();
            coTenantRepo.saveAll(coTenants);
            log.info("[EContract] Saved {} co-tenants for contractId={}", coTenants.size(), e.getId());
        }

        return e;
    }

    private byte[] renderHtmlToPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String baseUri = Objects.requireNonNull(getClass().getResource("/")).toExternalForm();
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_1_A);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2.ttf"),
                    "Times New Roman", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2 bold.ttf"),
                    "Times New Roman", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2 italic.ttf"),
                    "Times New Roman", 400, BaseRendererBuilder.FontStyle.ITALIC, true);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2 bold italic.ttf"),
                    "Times New Roman", 700, BaseRendererBuilder.FontStyle.ITALIC, true);
            b.withHtmlContent(toXhtml(html, baseUri), baseUri);
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF render failed: " + e.getMessage(), e);
        }
    }

    private String toXhtml(String html, String baseUri) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUri);
        if (doc.head().selectFirst("meta[charset]") == null)
            doc.head().prependElement("meta").attr("charset", "UTF-8");
        if (doc.head().selectFirst("base[href]") == null)
            doc.head().prependElement("base").attr("href", baseUri);
        doc.outputSettings().charset("UTF-8")
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml);
        return doc.html();
    }

    private InputStream cp(String p) {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(p);
        if (in == null) throw new IllegalStateException("Missing classpath: " + p);
        return in;
    }

    private String buildAssetTable(UUID houseId) {
        java.util.LinkedHashMap<String, Integer> grouped = new java.util.LinkedHashMap<>();
        for (AssetItemDto item : assetGrpc.getAssetItemsByHouseId(houseId)) {
            String name = item.getDisplayName() == null ? "" : item.getDisplayName().trim();
            if (name.isEmpty()) continue;
            grouped.merge(name, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"width:100%;border-collapse:collapse;\">")
                .append("<thead><tr>")
                .append("<th style=\"border:1px solid #000;padding:6px;width:10%;\">STT</th>")
                .append("<th style=\"border:1px solid #000;padding:6px;\">Tên tài sản</th>")
                .append("<th style=\"border:1px solid #000;padding:6px;width:15%;\">Số lượng</th>")
                .append("</tr></thead><tbody>");
        int i = 1;
        for (java.util.Map.Entry<String, Integer> entry : grouped.entrySet()) {
            sb.append("<tr>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">").append(i++).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;\">")
                    .append(HtmlUtils.htmlEscape(entry.getKey())).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">")
                    .append(entry.getValue()).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String renderPlaceholders(String tmpl, Map<String, Object> data) {
        Matcher m = Pattern.compile("\\{\\{\\s*([A-Z0-9_][A-Z0-9_.]*)\\s*}}").matcher(tmpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = data.get(key);
            if (v == null) throw new IllegalStateException("Missing placeholder: " + key);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Map<String, AnchorBoxVnpt> findAnchors(byte[] pdf, List<String> texts) {
        Set<String> remain = new LinkedHashSet<>(texts);
        Map<String, AnchorBoxVnpt> found = new HashMap<>();
        int kt = Math.max(256, texts.stream().mapToInt(String::length).max().orElse(32) * 8);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (int pi = 0; pi < doc.getNumberOfPages() && !remain.isEmpty(); pi++) {
                PDRectangle box = pageBox(doc.getPage(pi));
                AnchorStripper s = new AnchorStripper(remain, found, box.getHeight(), pi + 1, kt);
                s.setSortByPosition(true);
                s.setSuppressDuplicateOverlappingText(false);
                s.setStartPage(pi + 1);
                s.setEndPage(pi + 1);
                s.getText(doc);
                remain.removeAll(found.keySet());
            }
            if (!remain.isEmpty()) throw new IllegalStateException("Anchors not found: " + remain);
            return found;
        } catch (IOException e) {
            throw new IllegalStateException("PDF parse failed", e);
        }
    }

    private PDRectangle pageBox(PDPage p) {
        return p.getCropBox() != null ? p.getCropBox() : p.getMediaBox();
    }

    private static final class AnchorStripper extends PDFTextStripper {
        private final Set<String> remain;
        private final Map<String, AnchorBoxVnpt> found;
        private final float ph;
        private final int pn;
        private final int kt;
        private final StringBuilder buf = new StringBuilder(512);
        private final ArrayList<TextPosition> pos = new ArrayList<>(512);

        AnchorStripper(Set<String> r, Map<String, AnchorBoxVnpt> f, float ph, int pn, int kt) throws IOException {
            super();
            remain = r;
            found = f;
            this.ph = ph;
            this.pn = pn;
            this.kt = kt;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (remain.isEmpty()) return;
            buf.append(text);
            pos.addAll(positions);
            if (buf.length() > kt * 2) {
                buf.delete(0, buf.length() - kt);
                if (pos.size() > kt) pos.subList(0, pos.size() - kt).clear();
            }
            String s = buf.toString();
            for (String a : new ArrayList<>(remain)) {
                int idx = s.lastIndexOf(a);
                if (idx < 0) continue;
                int pi2 = idx - (s.length() - pos.size());
                if (pi2 < 0 || pi2 >= pos.size()) continue;
                List<TextPosition> ap = pos.subList(pi2, Math.min(pi2 + a.length(), pos.size()));
                if (ap.isEmpty()) continue;
                float xMin = Float.MAX_VALUE, yMax = 0f;
                for (TextPosition tp : ap) {
                    float x = tp.getXDirAdj();
                    if (x < xMin) xMin = x;
                    float y = tp.getYDirAdj() + tp.getHeightDir();
                    if (y > yMax) yMax = y;
                }
                found.put(a, new AnchorBoxVnpt(pn, xMin, ph - yMax));
            }
        }
    }

    public VnptPosition getVnptPosition(byte[] pdf, AnchorBoxVnpt anchor,
                                        double w, double h, double oY, double mg, double xAdj, double yAdj, double es, double tp) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int last = doc.getNumberOfPages();
            PDRectangle box = pageBox(doc.getPage(anchor.page() - 1));
            double pw = box.getWidth(), ph = box.getHeight();
            double lly = anchor.bottom() - oY + yAdj - es;
            if (lly - h < mg) {
                if (anchor.page() >= last) {
                    lly = mg;
                } else {
                    PDRectangle nb = pageBox(doc.getPage(anchor.page()));
                    return new VnptPosition(
                            buildPos(clamp(anchor.left() + xAdj, mg, nb.getWidth() - mg - w),
                                    clamp(nb.getHeight() - mg - h - tp + yAdj, mg, nb.getHeight() - mg - h), w, h),
                            anchor.page() + 1);
                }
            }
            return new VnptPosition(buildPos(clamp(anchor.left() + xAdj, mg, pw - mg - w), mg, w, h), anchor.page());
        } catch (IOException e) {
            throw new IllegalStateException("PDF read failed", e);
        }
    }

    private double clamp(double v, double min, double max) {
        return max < min ? min : Math.max(min, Math.min(max, v));
    }

    private String buildPos(double llx, double lly, double w, double h) {
        return (int) Math.round(llx) + "," + (int) Math.round(lly) + "," + (int) Math.round(llx + w) + "," + (int) Math.round(lly + h);
    }

    private ProcessLoginInfoDto parseProcessLogin(String body) {
        try {
            JsonNode root = json.readTree(body);
            if (root.has("success") && !root.get("success").asBoolean(true)) {
                JsonNode messages = root.get("messages");
                String message = root.hasNonNull("message") ? root.get("message").asText() : null;
                if ((message == null || message.isBlank()) && messages != null && messages.isArray() && !messages.isEmpty()) {
                    message = messages.get(0).asText();
                }
                throw new IllegalStateException(message == null || message.isBlank()
                        ? "VNPT rejected process code"
                        : message);
            }
            JsonNode d = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
            String token = null;
            JsonNode tn = d.get("token");
            if (tn != null && !tn.isNull())
                token = tn.isTextual() ? tn.asText()
                        : Optional.ofNullable(tn.get("accessToken")).map(JsonNode::asText).orElse(null);
            JsonNode doc = d.get("document");
            if (doc == null || !doc.isObject())
                throw new IllegalStateException("Missing 'document': " + body);
            String docId = tx(doc, "id"), docNo = tx(doc, "no"), waitId = null, pos = null;
            Integer pBy = null, ps = null;
            boolean isOtp = false;
            JsonNode w = doc.get("waitingProcess");
            if (w != null && w.isObject()) {
                waitId = tx(w, "id");
                pBy = in(w, "processedByUserId");
                pos = tx(w, "position");
                ps = in(w, "pageSign");
                JsonNode ap = w.get("accessPermission");
                if (ap != null && ap.isObject()) {
                    JsonNode v = ap.get("value");
                    isOtp = v != null && v.canConvertToInt() && v.asInt() == 7;
                }
            }
            return new ProcessLoginInfoDto(waitId, docId, docNo, pBy, token, pos, ps, null);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse VNPT response: " + e.getMessage() + "\nRAW=" + body, e);
        }
    }

    private void sendContractCompletedEvent(EContract contract, String signedPdfUrl) {
        try {
            String tenantEmail = contract.getTenantEmail();
            Boolean isNewAccount = null;
            try {
                UserResponse tenant = userGrpc.getUserById(contract.getUserId().toString());
                if (tenant != null && !tenant.getEmail().isBlank()) {
                    tenantEmail = tenant.getEmail();
                    isNewAccount = !tenant.getIsEnabled();
                }
            } catch (Exception e) {
                log.warn("[EContract] Cannot fetch tenant by userId={}, will retry by email={} : {}",
                        contract.getUserId(), tenantEmail, e.getMessage());
                if (tenantEmail != null && !tenantEmail.isBlank()) {
                    try {
                        UserResponse tenantByEmail = userGrpc.getUserByEmail(tenantEmail);
                        if (tenantByEmail != null) {
                            isNewAccount = !tenantByEmail.getIsEnabled();
                            log.info("[EContract] Resolved tenant by email fallback userId={} email={} isNewAccount={}",
                                    contract.getUserId(), tenantEmail, isNewAccount);
                        }
                    } catch (Exception emailEx) {
                        log.warn("[EContract] Email-based tenant lookup also failed email={}: {}",
                                tenantEmail, emailEx.getMessage());
                    }
                }
            }

            if (tenantEmail == null || tenantEmail.isBlank()) {
                log.error("[EContract] sendContractCompletedEvent: tenantEmail UNRESOLVED contractId={} userId={} — deposit invoice will be created but no payment email will be sent. Manager must call POST /api/econtracts/{id}/admin/resend-completion to recover.",
                        contract.getId(), contract.getUserId());
                sendWsStatus(contract.getId(), "TENANT_EMAIL_MISSING",
                        "Hợp đồng đã ký xong nhưng không gửi được email thanh toán cọc do không xác định được email người thuê. Vui lòng vào trang quản lý để gửi lại.");
            }

            Instant depositDueAt = null;
            Long billableDeposit = billableDepositAmount(contract);
            if (billableDeposit != null && billableDeposit > 0) {
                int waitDays = landlordRepo.findByUserId(contract.getCreatedBy())
                        .map(LandlordProfile::getDepositWaitDays)
                        .filter(d -> d != null && d > 0)
                        .orElse(3);
                depositDueAt = Instant.now().plus(waitDays, ChronoUnit.DAYS);
                contract.setDepositDueAt(depositDueAt);
                contractRepo.save(contract);
            }

            Long originalDeposit = contract.getDepositAmount();
            Long transferredDeposit = contract.getTransferredDepositAmount();
            UUID relocationSource = contract.getRelocationSourceContractId();
            ContractCompletedEvent event = ContractCompletedEvent.builder()
                    .contractId(contract.getId())
                    .tenantId(contract.getUserId())
                    .tenantEmail(tenantEmail)
                    .isNewAccount(isNewAccount)
                    .houseId(contract.getHouseId())
                    .landlordId(contract.getCreatedBy())
                    .depositAmount(billableDeposit)
                    .originalDepositAmount(originalDeposit)
                    .transferredDepositAmount(transferredDeposit)
                    .relocationSourceContractId(relocationSource)
                    .rentAmount(contract.getRentAmount())
                    .payDate(contract.getPayDate())
                    .startAt(contract.getStartAt())
                    .endAt(contract.getEndAt())
                    .completedAt(Instant.now())
                    .depositDueAt(depositDueAt)
                    .signedPdfUrl(signedPdfUrl)
                    .build();

            String completedMsgId = UUID.randomUUID().toString();
            outboxPublisher.enqueue(
                    "contract-completed-topic",
                    contract.getId().toString(),
                    event,
                    completedMsgId);

            String jobMsgId = UUID.randomUUID().toString();
            outboxPublisher.enqueue(
                    "job.created",
                    contract.getId().toString(),
                    JobCreatedEvent.builder()
                            .referenceId(contract.getId())
                            .houseId(contract.getHouseId())
                            .referenceType("INSPECTION")
                            .type("CHECK_IN")
                            .messageId(jobMsgId)
                            .build(),
                    jobMsgId);

            renewalRequestRepo.findByNewContractId(contract.getId()).ifPresent(r -> {
                r.setStatus(RenewalRequestStatus.COMPLETED);
                r.setResolvedAt(Instant.now());
                renewalRequestRepo.save(r);
                log.info("[Renewal] Auto-completed renewalRequestId={} newContractId={}",
                        r.getId(), contract.getId());
            });

            completeRelocationReplacement(contract);
        } catch (Exception e) {
            log.error("[EContract] sendContractCompletedEvent failed contractId={}: {}", contract.getId(), e.getMessage(), e);
        }
    }

    private Long billableDepositAmount(EContract contract) {
        long deposit = contract.getDepositAmount() == null ? 0L : contract.getDepositAmount();
        long transferred = contract.getTransferredDepositAmount() == null ? 0L : contract.getTransferredDepositAmount();
        return Math.max(0L, deposit - transferred);
    }

    private DepositStatus resolveCompletedDepositStatus(EContract contract) {
        long deposit = contract.getDepositAmount() == null ? 0L : contract.getDepositAmount();
        long transferred = contract.getTransferredDepositAmount() == null ? 0L : contract.getTransferredDepositAmount();
        if (deposit == 0L) {
            return DepositStatus.PAID;
        }
        if (contract.getRelocationSourceContractId() != null && transferred >= deposit) {
            return DepositStatus.TRANSFERRED;
        }
        if (contract.getRelocationSourceContractId() != null && transferred > 0L) {
            return DepositStatus.PARTIALLY_TRANSFERRED;
        }
        return DepositStatus.PENDING;
    }

    private void completeRelocationReplacement(EContract replacement) {
        if (replacement.getRelocationSourceContractId() == null) {
            return;
        }

        relocationRequestRepo.findByNewContractId(replacement.getId()).ifPresent(relocation -> {
            if (relocation.getStatus() == RelocationRequestStatus.COMPLETED) {
                return;
            }
            if (relocation.getRequestKind() == RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE) {
                return;
            }

            EContract old = contractRepo.findById(replacement.getRelocationSourceContractId())
                    .orElseThrow(() -> new NotFoundException("Source relocation contract not found: "
                            + replacement.getRelocationSourceContractId()));

            if (old.getReplacedByContractId() != null) {
                relocation.setStatus(RelocationRequestStatus.COMPLETED);
                relocation.setCompletedAt(Instant.now());
                relocationRequestRepo.save(relocation);
                publishContractReplaced(old, replacement, relocation);
                return;
            }

            DepositHandling handling = relocation.getDepositHandling();
            if (handling == DepositHandling.TRANSFER_TO_REPLACEMENT) {
                old.setDepositStatus(DepositStatus.TRANSFERRED);
            } else if (handling == DepositHandling.PARTIAL_TRANSFER) {
                old.setDepositStatus(DepositStatus.PARTIALLY_TRANSFERRED);
            } else if (handling == DepositHandling.FORFEIT) {
                old.setDepositStatus(DepositStatus.FORFEITED);
            } else if (handling == DepositHandling.CANCEL_PENDING_DEPOSIT) {
                old.setDepositStatus(DepositStatus.UNPAID);
            }

            EContractStatus replacedStatus = isPaidDeposit(relocation.getDepositStatusSnapshot())
                    ? EContractStatus.REPLACED_AFTER_DEPOSIT
                    : EContractStatus.REPLACED_BEFORE_DEPOSIT;
            old.getStatus().validateTransition(replacedStatus);
            old.setStatus(replacedStatus);
            old.setReplacedByContractId(replacement.getId());
            contractRepo.save(old);

            relocation.setStatus(RelocationRequestStatus.COMPLETED);
            relocation.setCompletedAt(Instant.now());
            relocationRequestRepo.save(relocation);
            publishContractReplaced(old, replacement, relocation);

            log.info("[Relocation] COMPLETED requestId={} oldContractId={} newContractId={}",
                    relocation.getId(), old.getId(), replacement.getId());
        });
    }

    private void publishContractReplaced(
            EContract old,
            EContract replacement,
            ContractRelocationRequest relocation) {
        boolean landlordFault = relocation.getFaultParty() == RelocationFaultParty.LANDLORD;
        UUID newHouseId = relocation.getApprovedHouseId() != null
                ? relocation.getApprovedHouseId()
                : (relocation.getRequestedHouseId() != null
                        ? relocation.getRequestedHouseId() : replacement.getHouseId());
        ContractReplacedEvent event = ContractReplacedEvent.builder()
                .messageId(UUID.randomUUID().toString())
                .oldContractId(old.getId())
                .newContractId(replacement.getId())
                .oldHouseId(old.getHouseId())
                .newHouseId(newHouseId)
                .tenantId(old.getUserId())
                .keepHouseUnavailable(landlordFault)
                .depositHandling(relocation.getDepositHandling() != null
                        ? relocation.getDepositHandling().name()
                        : null)
                .transferredDepositAmount(relocation.getTransferredDepositAmount() != null
                        ? relocation.getTransferredDepositAmount() : 0L)
                .reason(landlordFault
                        ? (relocation.getStaffReportReason() != null && !relocation.getStaffReportReason().isBlank()
                                ? relocation.getStaffReportReason()
                                : "Landlord-fault relocation")
                        : "Tenant-initiated relocation")
                .replacedAt(Instant.now())
                .newHandoverDate(relocation.getNewHandoverDate() != null
                        ? relocation.getNewHandoverDate()
                        : replacement.getHandoverDate())
                .build();
        outboxPublisher.enqueue(
                "contract.replaced",
                old.getId().toString(),
                event,
                event.getMessageId());
    }

    private boolean isPaidDeposit(DepositStatus status) {
        return status == DepositStatus.PAID
                || status == DepositStatus.TRANSFERRED
                || status == DepositStatus.PARTIALLY_TRANSFERRED;
    }

    private void sendContractCancelledByTenantEvent(EContract contract) {
        try {
            ContractCancelledByTenantEvent event = ContractCancelledByTenantEvent.builder()
                    .messageId(UUID.randomUUID().toString())
                    .contractId(contract.getId())
                    .houseId(contract.getHouseId())
                    .tenantId(contract.getUserId())
                    .tenantName(contract.getTenantName())
                    .reason(contract.getTerminatedReason())
                    .cancelledAt(contract.getTerminatedAt())
                    .initiatedByUserId(contract.getCreatedBy())
                    .build();

            kafka.send("contract.cancelled-by-tenant", contract.getId().toString(), event)
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            log.error("[EContract] Kafka FAILED cancelled-by-tenant contractId={}: {}",
                                    contract.getId(), ex.getMessage(), ex);
                        } else {
                            log.info("[EContract] Kafka OK cancelled-by-tenant contractId={}", contract.getId());
                        }
                    });
        } catch (Exception e) {
            log.error("[EContract] sendContractCancelledByTenantEvent failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
        }
    }

    private void sendReadyForLandlordSignatureEvent(EContract contract) {
        if (contract.getCreatedBy() == null) {
            log.warn("[EContract] Skip ready notification because createdBy is null contractId={}", contract.getId());
            return;
        }

        try {
            ContractReadyForLandlordSignatureEvent event = ContractReadyForLandlordSignatureEvent.builder()
                    .messageId(UUID.randomUUID().toString())
                    .contractId(contract.getId())
                    .houseId(contract.getHouseId())
                    .recipientUserId(contract.getCreatedBy())
                    .tenantId(contract.getUserId())
                    .tenantName(contract.getTenantName())
                    .contractName(contract.getName())
                    .documentId(contract.getDocumentId())
                    .build();

            outboxPublisher.enqueue(
                    "contract.ready-for-landlord-signature",
                    contract.getId().toString(),
                    event,
                    event.messageId());
        } catch (Exception e) {
            log.error("[EContract] sendReadyForLandlordSignatureEvent failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
        }
    }

    private void updateSnapshotFromVnpt(EContract contract) {
        try {
            VnptResult<VnptDocumentDto> docResult = vnptClient.getEContractById(
                    contract.getDocumentId(), vnptClient.getToken());

            if (docResult == null || docResult.getData() == null
                    || docResult.getData().downloadUrl() == null) {
                log.warn("[EContract] No downloadUrl from VNPT contractId={} — skip snapshot update",
                        contract.getId());
                return;
            }

            byte[] signedPdf = vnptClient.downloadSignedPdf(docResult.getData().downloadUrl());

            if (signedPdf == null || signedPdf.length == 0) {
                log.warn("[EContract] Downloaded PDF empty contractId={}", contract.getId());
                return;
            }

            s3.deleteIfExists(contract.getSnapshotKey());
            String newKey = s3.uploadContractPdf(signedPdf, contract.getId());
            contract.setSnapshotKey(newKey);
            contractRepo.save(contract);

            log.info("[EContract] Snapshot updated contractId={} key={} size={}KB",
                    contract.getId(), newKey, signedPdf.length / 1024);

        } catch (Exception e) {
            log.error("[EContract] updateSnapshotFromVnpt failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
        }
    }

    private void fetchAndStoreSignedPdf(EContract contract) {
        try {
            VnptResult<VnptDocumentDto> docResult = vnptClient.getEContractById(
                    contract.getDocumentId(), vnptClient.getToken());

            if (docResult == null || docResult.getData() == null || docResult.getData().downloadUrl() == null) {
                log.warn("[EContract] No downloadUrl contractId={} — sending event without PDF",
                        contract.getId());
                sendContractCompletedEvent(contract, null);
                return;
            }

            byte[] signedPdf = vnptClient.downloadSignedPdf(docResult.getData().downloadUrl());

            if (signedPdf == null || signedPdf.length == 0) {
                log.warn("[EContract] PDF empty contractId={}", contract.getId());
                sendContractCompletedEvent(contract, null);
                return;
            }

            s3.deleteIfExists(contract.getSnapshotKey());
            String signedKey = s3.uploadContractPdf(signedPdf, contract.getId());
            contract.setSnapshotKey(signedKey);
            contractRepo.save(contract);

            log.info("[EContract] Signed PDF stored contractId={} key={} size={}KB",
                    contract.getId(), signedKey, signedPdf.length / 1024);

            String pdfUrl = s3.presignedUrl(signedKey, 7 * 24 * 60);
            sendContractCompletedEvent(contract, pdfUrl);

        } catch (Exception e) {
            log.error("[EContract] fetchAndStoreSignedPdf failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
            sendContractCompletedEvent(contract, null);
        }
    }

    private record ContractScope(
            Scope kind,
            UUID actorId,
            java.util.Set<UUID> managedHouseIds
    ) {
        enum Scope { LANDLORD_ALL, MANAGER_REGION, TENANT_OWN, DENIED }

        String cacheKey() {
            return switch (kind) {
                case LANDLORD_ALL -> "landlord";
                case MANAGER_REGION -> "mgr-" + actorId;
                case TENANT_OWN -> "tnt-" + actorId;
                case DENIED -> "deny";
            };
        }
    }

    private ContractScope resolveScope(org.springframework.security.core.Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) {
            return new ContractScope(ContractScope.Scope.DENIED, null, java.util.Set.of());
        }
        UUID keycloakId;
        try {
            keycloakId = UUID.fromString(auth.getName());
        } catch (IllegalArgumentException ex) {
            return new ContractScope(ContractScope.Scope.DENIED, null, java.util.Set.of());
        }

        boolean isLandlord = hasAuthority(auth, "ROLE_LANDLORD");
        boolean isManager = hasAuthority(auth, "ROLE_MANAGER");
        boolean isTenant = hasAuthority(auth, "ROLE_TENANT");

        if (isLandlord) {
            return new ContractScope(ContractScope.Scope.LANDLORD_ALL, keycloakId, java.util.Set.of());
        }

        UUID internalId;
        try {
            internalId = resolveInternalTenantId(keycloakId);
        } catch (Exception ex) {
            log.warn("[Scope] Failed to resolve internal user id for keycloakId={}: {}", keycloakId, ex.getMessage());
            return new ContractScope(ContractScope.Scope.DENIED, keycloakId, java.util.Set.of());
        }

        if (isManager) {
            java.util.Set<UUID> managed;
            try {
                managed = ((HouseGrpcClient) houseGrpc).getManagedHouseIds(internalId);
            } catch (Exception ex) {
                log.warn("[Scope] Failed to resolve managed houses for managerId={}: {}", internalId, ex.getMessage());
                managed = java.util.Set.of();
            }
            return new ContractScope(ContractScope.Scope.MANAGER_REGION, internalId, managed);
        }
        if (isTenant) {
            return new ContractScope(ContractScope.Scope.TENANT_OWN, internalId, java.util.Set.of());
        }
        return new ContractScope(ContractScope.Scope.DENIED, internalId, java.util.Set.of());
    }

    private static boolean hasAuthority(org.springframework.security.core.Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private void requireAccess(EContract contract, org.springframework.security.core.Authentication auth) {
        ContractScope scope = resolveScope(auth);
        switch (scope.kind()) {
            case LANDLORD_ALL -> {  }
            case MANAGER_REGION -> {
                if (!scope.managedHouseIds().contains(contract.getHouseId())) {
                    throw new AccessDeniedException("Contract belongs to a region you do not manage");
                }
            }
            case TENANT_OWN -> {
                if (!contract.getUserId().equals(scope.actorId())) {
                    throw new AccessDeniedException("You may only view your own contracts");
                }
            }
            case DENIED -> throw new AccessDeniedException("No permission to access this contract");
        }
    }

    private PageResponse<EContractDto> loadPage(PageRequest request, ContractScope scope) {
        EContractStatus statusFilter = request.<String>filterValue("status")
                .map(s -> {
                    try {
                        return EContractStatus.valueOf(s.toUpperCase().trim());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .orElse(null);

        String statusesRaw = request.<String>filterValue("statuses").orElse(null);

        String houseIdRaw = request.<String>filterValue("houseId").orElse(null);
        UUID houseIdFilter = houseIdRaw != null ? UUID.fromString(houseIdRaw) : null;

        var specBuilder = SpecificationBuilder.<EContract>create()
                .keywordLike(request.keyword(), "name", "tenantName", "documentNo", "documentId")
                .enumEq("status", statusFilter)
                .enumInRaw("status", statusesRaw, EContractStatus.class)
                .eq("houseId", houseIdFilter);

        switch (scope.kind()) {
            case LANDLORD_ALL -> {  }
            case MANAGER_REGION -> {
                if (scope.managedHouseIds().isEmpty()) {

                    return PageResponse.<EContractDto>empty();
                }
                specBuilder.in("houseId", scope.managedHouseIds());
            }
            case TENANT_OWN -> specBuilder.eq("userId", scope.actorId());
            case DENIED -> {
                return PageResponse.<EContractDto>empty();
            }
        }

        var spec = specBuilder.build();
        var pageable = SpringPageConverter.toPageable(request);

        Page<EContract> page = contractRepo.findAll(spec, pageable);

        return SpringPageConverter.fromPage(page, mapper::contractToDto);
    }

    private String resolvePdfUrlForTenant(EContract c) {
        if (c.getSnapshotKey() == null) return null;
        return switch (c.getStatus()) {
            case PENDING_TENANT_REVIEW,
                 READY,
                 IN_PROGRESS,
                 COMPLETED -> s3.presignedUrl(c.getSnapshotKey(), 60);
            default -> null;
        };
    }

    private String tx(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }

    private Integer in(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.canConvertToInt() ? v.asInt() : null;
    }

    private String nvl(String v, String fb) {
        return v != null && !v.isBlank() ? v : fb;
    }

    private VnptDocumentDto ensureVnptDocument(EContract c, byte[] finalPdf, String token) {
        if (c.getDocumentId() != null && !c.getDocumentId().isBlank()) {
            try {
                VnptResult<VnptDocumentDto> r = vnptClient.getEContractById(c.getDocumentId(), token);
                if (r != null && r.getData() != null && r.getData().id() != null) {
                    log.info("[VNPT] Reusing existing documentId={} for contractId={}",
                            c.getDocumentId(), c.getId());
                    return r.getData();
                }
                log.warn("[VNPT] Prior documentId={} not found at VNPT — creating new one (orphan safe to ignore)",
                        c.getDocumentId());
            } catch (Exception ex) {
                log.warn("[VNPT] Lookup existing documentId={} failed ({}) — proceeding to create new",
                        c.getDocumentId(), ex.getMessage());
            }
        }
        String docNo = "EC_" + System.currentTimeMillis();
        VnptResult<VnptDocumentDto> createResult = vnptClient.createDocument(token, new CreateDocumentDto(
                new FileInfoDto(null, finalPdf, docNo + ".pdf"),
                "Rental EContract", "Rental EContract", 3059, 3110, docNo));
        if (createResult == null || createResult.getData() == null) {
            throw new IllegalStateException("VNPT document creation failed: "
                    + (createResult != null ? createResult.getMessage() : "null"));
        }
        return createResult.getData();
    }

    private void purgePiiObjects(EContract c) {
        try {
            s3.deleteIfExists(c.getSnapshotKey());
            s3.deleteIfExists(c.getCccdFrontKey());
            s3.deleteIfExists(c.getCccdBackKey());
            s3.deleteIfExists(c.getPassportFrontKey());
            c.setSnapshotKey(null);
            c.setCccdFrontKey(null);
            c.setCccdBackKey(null);
            c.setPassportFrontKey(null);
        } catch (Exception ex) {
            log.error("[EContract] purgePii failed contractId={} — S3 lifecycle will retry: {}",
                    c.getId(), ex.getMessage());
        }
    }

    private EContract findById(UUID id) {
        return contractRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + id));
    }

    private EContract findByDocumentId(String documentId) {
        return contractRepo.findByDocumentId(documentId)
                .orElseThrow(() -> new NotFoundException("Contract not found for documentId: " + documentId));
    }

    private UUID resolveInternalTenantId(UUID callerId) {
        UserResponse user = userGrpc.getUserIdAndRoleByKeyCloakId(callerId.toString());
        return UUID.fromString(user.getId());
    }

    private Optional<UUID> findExistingTenantIdByEmail(String email, String jwtToken) {
        try {
            UserResponse existing = userGrpc.getUserByEmail(email, jwtToken);
            if (existing == null || existing.getId() == null || existing.getId().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(existing.getId()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private String resolveTenantEmail(UUID tenantUserId) {
        if (tenantUserId == null) return null;
        try {
            UserResponse user = userGrpc.getUserById(tenantUserId.toString());
            return user != null ? user.getEmail() : null;
        } catch (StatusRuntimeException e) {
            log.warn("[EContract] resolveTenantEmail failed userId={} code={}: {}",
                    tenantUserId, e.getStatus().getCode(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[EContract] resolveTenantEmail unexpected userId={}: {}", tenantUserId, e.getMessage());
            return null;
        }
    }

    private String resolveUserLanguage(CreateEContractRequest req) {
        com.isums.contractservice.domains.enums.ContractLanguage lang = req.contractLanguageOrDefault();
        return switch (lang) {
            case VI -> "vi_VN";
            case VI_EN -> "en_US";
            case VI_JA -> "ja_JP";
        };
    }
}
