package com.isums.contractservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.assetservice.grpc.AssetItemDto;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.domains.entities.*;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.*;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.exceptions.OcrValidationException;
import com.isums.contractservice.infrastructures.abstracts.*;
import com.isums.contractservice.infrastructures.grpcs.*;
import com.isums.contractservice.infrastructures.mappers.EContractMapper;
import com.isums.contractservice.infrastructures.repositories.*;
import com.isums.contractservice.infrastructures.specifications.EContractSpec;
import com.isums.contractservice.utils.NumberToTextConverter;
import com.isums.houseservice.grpc.HouseResponse;
import com.isums.userservice.grpc.UserResponse;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import common.paginations.dtos.PageRequest;
import common.paginations.dtos.PageResponse;
import common.paginations.services.CachedPageService;
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
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final KeycloakAdminServiceImpl keycloakAdmin;
    private final ContractTokenService contractTokenService;

    private static final String PAGE_NS = "econtracts";
    private static final Duration PAGE_TTL = Duration.ofMinutes(60);

    private final CachedPageService cachedPageService;

    @Value("${ocr.service.url}")
    private String ocrUrl;

    @Value("${vnpt.landlord.username}")
    private String vnptLandlordUsername;

    @Value("${app.contract.view-url:https://isums.pro/contracts}")
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
    public EContractDto createDraft(UUID actorId, String jwtToken, CreateEContractRequest req) {
        try {
            UUID tenantId;
            if (!req.isNewAccount()) {
                tenantId = UUID.fromString(userGrpc.getUserByEmail(req.email(), jwtToken).getId());
            } else {
                tenantId = UUID.randomUUID();
                createVnptUser(vnptClient.getToken(), tenantId, req);
                kafka.send("createUser-topic", CreateUserPlacedEvent.builder()
                        .id(tenantId).name(req.name()).email(req.email())
                        .phoneNumber(req.phoneNumber()).identityNumber(req.identityNumber())
                        .isEnabled(false).build());
            }

            HouseResponse house = houseGrpc.getHouseById(req.houseId());
            if (house == null) throw new NotFoundException("House not found: " + req.houseId());

            cachedPageService.evictAll(PAGE_NS);

            return mapper.contractToDto(buildAndSaveDraft(actorId, tenantId, req, house));

        } catch (IllegalStateException | NotFoundException e) {
            throw e;
        } catch (Exception ex) {
            log.error("createDraft failed", ex);
            throw new IllegalStateException("Tạo hợp đồng thất bại: " + ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EContractDto getById(UUID id) {
        EContract contract = findById(id);
        if (contract.getStatus() != EContractStatus.DRAFT && contract.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {

            String pdfUrl = getPdfPresignedUrlForAdmin(id);

            EContractDto dto = mapper.contractToDto(contract);
            return dto.updatePdfUrl(pdfUrl);
        }
        return mapper.contractToDto(contract);
    }

    @Override
    public PageResponse<EContractDto> getAll(PageRequest request) {
        return cachedPageService.getOrLoad(PAGE_NS, request, PAGE_TTL,
                new TypeReference<>() {
                }, () -> loadPage(request)
        );
    }

    @Override
    @Transactional
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

            sendWsStatus(c.getId(), "CORRECTING", "Hợp đồng đang được hiệu chỉnh bởi chủ nhà. Vui lòng chờ phiên bản mới.");

            log.info("[EContract] CORRECTING contractId={}", id);

            cachedPageService.evictAll(PAGE_NS);

        } else if (current == EContractStatus.DRAFT || current == EContractStatus.CORRECTING) {
            mapper.patch(req, c);
            contractRepo.save(c);

        } else {
            throw new IllegalStateException("Không thể chỉnh sửa hợp đồng ở trạng thái: " + current);
        }

        return mapper.contractToDto(c);
    }

    @Override
    @Transactional
    public EContractDto confirmByAdmin(UUID contractId, UUID actorId) {
        EContract c = findById(contractId);
        EContractStatus cur = c.getStatus();

        if (cur != EContractStatus.DRAFT && cur != EContractStatus.CORRECTING && cur != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Chỉ confirm được ở trạng thái DRAFT, PENDING_TENANT_REVIEW hoặc CORRECTING. Hiện tại: " + cur);
        }

        byte[] pdfBytes = renderHtmlToPdf(c.getHtml());

        s3.deleteIfExists(c.getSnapshotKey());
        String snapshotKey = s3.uploadContractPdf(pdfBytes, contractId);

        c.setSnapshotKey(snapshotKey);
        c.getStatus().validateTransition(EContractStatus.PENDING_TENANT_REVIEW);
        c.setStatus(EContractStatus.PENDING_TENANT_REVIEW);
        contractRepo.save(c);

        String magicToken = contractTokenService.generateToken(contractId, c.getUserId());
        String pdfViewUrl = s3.presignedUrl(snapshotKey, 24 * 60);
        String confirmUrl = contractViewBaseUrl + "/" + contractId + "/confirm?token=" + magicToken;

        ConfirmAndSendToTenantEvent event = ConfirmAndSendToTenantEvent.builder()
                .messageId(UUID.randomUUID().toString())
                .recipientUserId(c.getUserId())
                .contractId(contractId)
                .contractName(c.getName())
                .url(pdfViewUrl)
                .confirmUrl(confirmUrl)
                .startDate(c.getStartAt())
                .endDate(c.getEndAt())
                .build();

        cachedPageService.evictAll(PAGE_NS);

        log.info("[EContract] Sending Kafka event contractId={} to userId={}", contractId, c.getUserId());

        kafka.send("confirmAndSendToTenant-topic", event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[EContract] Kafka send FAILED contractId={}: {}", contractId, ex.getMessage(), ex);
                    } else {
                        log.info("[EContract] Kafka send OK contractId={} offset={}",
                                contractId, result.getRecordMetadata().offset());
                    }
                });

        log.info("[EContract] PENDING_TENANT_REVIEW contractId={} snapshotKey={}", contractId, snapshotKey);
        return mapper.contractToDto(c);
    }

    @Transactional(readOnly = true)
    public String getPdfPresignedUrlForAdmin(UUID contractId) {
        EContract c = findById(contractId);

        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("Hợp đồng chưa được tạo PDF.");
        }

        return s3.presignedUrl(c.getSnapshotKey(), 60);
    }

    @Override
    @Transactional(readOnly = true)
    public String getPdfPresignedUrl(UUID contractId, String contractToken) {
        contractTokenService.validateToken(contractToken, contractId);
        EContract c = findById(contractId);

        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("Hợp đồng chưa được tạo PDF.");
        }

        return s3.presignedUrl(c.getSnapshotKey(), pdfUrlTtlMinutes);
    }

    @Override
    @Transactional
    public VnptDocumentDto tenantConfirmWithCccd(UUID contractId, MultipartFile frontImage, MultipartFile backImage, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);

        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Hợp đồng không ở trạng thái chờ xác nhận. Hiện tại: " + c.getStatus());
        }
        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("Hợp đồng chưa có PDF snapshot. Vui lòng liên hệ chủ nhà.");
        }

        validateImage(frontImage, "mặt trước");
        validateImage(backImage, "mặt sau");

        OcrResult ocrFront = callOcrFrontAndValidate(
                frontImage, c.getTenantIdentityNumber(), c.getTenantName());
        validateCccdBack(backImage);

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

        String docNo = "EC_" + System.currentTimeMillis();
        String token = vnptClient.getToken();
        VnptResult<VnptDocumentDto> createResult = vnptClient.createDocument(token, new CreateDocumentDto(
                new FileInfoDto(null, finalPdf, docNo + ".pdf"),
                "Rental EContract", "Rental EContract", 3059, 3110, docNo));

        if (createResult == null || createResult.getData() == null) {
            throw new IllegalStateException("VNPT tạo document thất bại: "
                    + (createResult != null ? createResult.getMessage() : "null"));
        }

        String documentId = createResult.getData().id();

        c.setDocumentId(documentId);
        c.setDocumentNo(createResult.getData().no());
        c.setCccdFrontKey(frontKey);
        c.setCccdBackKey(backKey);
        c.setCccdVerifiedAt(Instant.now());
        c.getStatus().validateTransition(EContractStatus.READY);
        c.setStatus(EContractStatus.READY);
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
            throw new IllegalStateException("VNPT sendProcess thất bại: "
                    + (sendResult != null ? sendResult.getMessage() : "null"));
        }

        log.info("[EContract] READY contractId={} documentId={} cccdId={}",
                contractId, documentId,
                ocrFront != null ? ocrFront.identityNumber() : "ocr-skipped");

        contractTokenService.invalidateToken(contractToken);

        cachedPageService.evictAll(PAGE_NS);

        return sendResult.getData();
    }

    @Override
    @Transactional
    public ProcessResponse signByLandlord(VnptProcessDto process) {
        VnptProcessDto withToken = process.withToken(vnptClient.getToken());
        VnptResult<ProcessResponse> result = vnptClient.signProcess(withToken);

        if (result == null || result.getData() == null) {
            throw new IllegalStateException("Landlord ký thất bại: "
                    + (result != null ? result.getMessage() : "null"));
        }

        if (result.getSuccess() && result.getData().id() != null) {
            EContract c = findByDocumentId(String.valueOf(result.getData().id()));
            c.getStatus().validateTransition(EContractStatus.IN_PROGRESS);
            c.setStatus(EContractStatus.IN_PROGRESS);
            contractRepo.save(c);
            log.info("[EContract] IN_PROGRESS (landlord signed) contractId={}", c.getId());
        }

        cachedPageService.evictAll(PAGE_NS);

        return result.getData();
    }

    @Override
    public ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode) {
        try {
            ProcessLoginInfoDto result = parseProcessLogin(vnptClient.getAccessInfoByProcessCode(processCode));
            EContract contract = contractRepo.findByDocumentId(result.documentId()).orElseThrow(() -> new NotFoundException("EContract not found"));
            if (contract.getSnapshotKey() == null) {
                throw new IllegalStateException("Hợp đồng chưa được tạo PDF.");
            }

            String pdfUrl = s3.presignedUrl(contract.getSnapshotKey(), pdfUrlTtlMinutes);
            log.info(pdfUrl);

            return result.updatePdfUrl(pdfUrl);
        } catch (Exception ex) {
            log.error("getAccessInfoByProcessCode failed processCode={}", processCode, ex);
            throw new IllegalStateException("Lấy thông tin ký thất bại: " + ex.getMessage());
        }
    }


    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "allEContracts", allEntries = true),
            @CacheEvict(cacheNames = "vnptProcessCode", key = "#process.processCode", allEntries = true)
    })
    public ProcessResponse signByTenant(VnptProcessDto process) {
        VnptResult<ProcessResponse> result = vnptClient.signProcess(process);

        if (result == null || result.getData() == null) {
            throw new IllegalStateException("Tenant ký thất bại: "
                    + (result != null ? result.getMessage() : "null"));
        }

        if (result.getSuccess() && result.getData().id() != null) {
            EContract c = findByDocumentId(String.valueOf(result.getData().id()));
            c.getStatus().validateTransition(EContractStatus.COMPLETED);
            c.setStatus(EContractStatus.COMPLETED);
            contractRepo.save(c);
            log.info("[EContract] COMPLETED contractId={}", c.getId());

            sendContractCompletedEvent(c);

            cachedPageService.evictAll(PAGE_NS);
        }

        return result.getData();
    }

    @Override
    @Transactional
    public void cancelByTenant(UUID contractId, String reason, UUID tenantUserId, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW && c.getStatus() != EContractStatus.IN_PROGRESS) {
            throw new IllegalStateException("Tenant chỉ huỷ được ở PENDING_TENANT_REVIEW hoặc IN_PROGRESS. Hiện tại: " + c.getStatus());
        }
        c.setStatus(EContractStatus.CANCELLED_BY_TENANT);
        c.setTerminatedAt(Instant.now());
        c.setTerminatedReason(reason);
        c.setTerminatedBy(c.getUserId());
        contractRepo.save(c);
        log.info("[EContract] CANCELLED_BY_TENANT contractId={}", contractId);

        contractTokenService.invalidateToken(contractToken);

        cachedPageService.evictAll(PAGE_NS);
    }

    @Override
    @Transactional
    public void cancelByLandlord(UUID contractId, String reason, UUID actorId) {
        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.CORRECTING
                && c.getStatus() != EContractStatus.READY) {
            throw new IllegalStateException("Landlord chỉ huỷ được ở CORRECTING hoặc READY. Hiện tại: " + c.getStatus());
        }
        c.setStatus(EContractStatus.CANCELLED_BY_LANDLORD);
        c.setTerminatedAt(Instant.now());
        c.setTerminatedReason(reason);
        c.setTerminatedBy(actorId);
        contractRepo.save(c);
        log.info("[EContract] CANCELLED_BY_LANDLORD contractId={}", contractId);

        cachedPageService.evictAll(PAGE_NS);
    }

    @Override
    @Transactional
    @CacheEvict(allEntries = true, value = "allEContracts")
    public void deleteContract(UUID contractId, UUID actorId) {
        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.DRAFT) {
            throw new IllegalStateException("Chỉ xóa được hợp đồng ở trạng thái DRAFT. Hiện tại: " + c.getStatus());
        }
        s3.deleteIfExists(c.getSnapshotKey());
        contractRepo.delete(c);
        log.info("[EContract] DELETED contractId={} by={}", contractId, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCccd(UUID contractId) {
        EContract c = findById(contractId);
        return c.getCccdFrontKey() != null && c.getCccdBackKey() != null;
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
    public void testPayment(UUID eContractId) {
        EContract contract = contractRepo.findById(eContractId).orElseThrow(() -> new NotFoundException("EContract not found"));

        sendContractCompletedEvent(contract);
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

    private OcrResult callOcrFrontAndValidate(MultipartFile file, String expectedId, String expectedName) {
        try {
            JsonNode node = callOcr(file, "/ocr/cccd");
            OcrResult result = OcrResult.from(node);

            if (!node.path("isFrontSide").asBoolean(false))
                throw new OcrValidationException(OcrValidationException.NOT_FRONT_SIDE, "Ảnh không phải mặt trước CCCD.");

            if (result.identityNumber() == null)
                throw new OcrValidationException(OcrValidationException.CANNOT_READ_ID, "Không đọc được số CCCD.");

            if (expectedId != null && !expectedId.isBlank()
                    && !result.identityNumber().equals(expectedId))
                throw new OcrValidationException(OcrValidationException.ID_MISMATCH, "Số CCCD không khớp hợp đồng.");

            if (result.fullName() != null && expectedName != null) {
                String normOcr = norm(result.fullName());
                String normExpected = norm(expectedName);
                if (!normOcr.equals(normExpected) && !normExpected.contains(normOcr) && !normOcr.contains(normExpected)) {
                    throw new OcrValidationException(OcrValidationException.NAME_MISMATCH, "Tên không khớp hợp đồng.");
                }
            } else if (result.fullName() == null) {
                log.warn("[OCR] Không đọc được tên, bỏ qua kiểm tra tên. id={}", result.identityNumber());
            }

            log.info("[OCR] Front OK id={} name={}", result.identityNumber(), result.fullName());
            return result;
        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Front service lỗi, bỏ qua validate: {}", e.getMessage());
            return null;
        }
    }

    private void validateCccdBack(MultipartFile file) {
        try {
            JsonNode node = callOcr(file, "/ocr/cccd/back");

            if (!node.path("isReadable").asBoolean(true))
                throw new OcrValidationException(OcrValidationException.IMAGE_NOT_READABLE, "Ảnh mặt sau không rõ nét.");

            if (!node.path("isBackSide").asBoolean(false))
                throw new OcrValidationException(OcrValidationException.NOT_BACK_SIDE, "Ảnh không phải mặt sau CCCD.");
            
        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Back service lỗi, bỏ qua validate: {}", e.getMessage());
        }
    }

    private JsonNode callOcr(MultipartFile file, String path) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        });
        String raw = RestClient.create().post()
                .uri(ocrUrl + path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(String.class);
        return json.readTree(raw);
    }

    private void validateImage(MultipartFile f, String side) {
        if (f == null || f.isEmpty())
            throw new IllegalArgumentException("Ảnh " + side + " không được để trống.");
        if (f.getContentType() == null || !f.getContentType().startsWith("image/"))
            throw new IllegalArgumentException("File " + side + " phải là ảnh.");
        if (f.getSize() < 50 * 1024)
            throw new IllegalArgumentException("Ảnh " + side + " quá nhỏ (< 50KB).");
        if (f.getSize() > 10 * 1024 * 1024)
            throw new IllegalArgumentException("Ảnh " + side + " quá lớn (> 10MB).");
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

    private void updateProcess(String token, String documentId, String userFirst, String userSecond,
                               String posA, String posB, int pageA, int pageB) {
        vnptClient.UpdateProcess(token, new VnptUpdateProcessDTO(documentId, true, List.of(
                new ProcessesRequestDTO(1, userFirst, "E", posA, pageA),
                new ProcessesRequestDTO(2, userSecond, "E", posB, pageB)
        )));
    }

    private void activateTenant(UUID userId) {
        try {
            UserResponse u = userGrpc.getUserById(userId.toString());
            if (u == null || u.getIsEnabled()) return;
            String pwd = keycloakAdmin.activateUser(u.getKeycloakId());
            kafka.send("user-activated-topic", UserActivatedEvent.builder()
                    .userId(userId).email(u.getEmail()).name(u.getName()).tempPassword(pwd).build());
        } catch (Exception e) {
            log.error("[Activate] Failed userId={}", userId, e);
        }
    }

    private void mapUserToHouse(UUID userId, UUID houseId) {
        try {
            EContract contract = contractRepo.findByHouseIdAndUserId(houseId, userId)
                    .orElse(null);

            kafka.send("map-user-to-house-topic", MapUserToHouseEvent.builder()
                    .userId(userId)
                    .houseId(houseId)
                    .handoverDate(contract != null ? contract.getHandoverDate() : null)
                    .build());

            log.info("[EContract] mapUserToHouse userId={} houseId={}", userId, houseId);
        } catch (Exception e) {
            log.error("[MapUserHouse] Failed userId={} houseId={}", userId, houseId, e);
        }
    }

    private void createVnptUser(String token, UUID id, CreateEContractRequest req) {
        VnptResult<List<VnptUserDto>> r = vnptClient.CreateOrUpdateUser(token,
                new VnptUserUpsert(id.toString(), req.email(), req.name(), req.email(),
                        req.phoneNumber(), 1, 0, 2, true, true, 1, -1,
                        new ArrayList<>(List.of(3110)),
                        new ArrayList<>(List.of(UUID.fromString("0aa2afc9-39c5-4652-baec-08ddc28cdda2")))));
        if (r == null || r.getData() == null)
            throw new IllegalStateException("Tạo user VNPT thất bại: "
                    + (r != null ? r.getMessage() : "null"));
    }

    private EContract buildAndSaveDraft(UUID actorId, UUID tenantId,
                                        CreateEContractRequest req, HouseResponse house) {
        EContractTemplate tmpl = templateRepo.findByCode("LEASE_HOUSE")
                .orElseThrow(() -> new IllegalStateException("Template LEASE_HOUSE not found"));
        LandlordProfile lp = landlordRepo.findByUserId(actorId)
                .orElseThrow(() -> new IllegalStateException(
                        "Landlord chưa cập nhật thông tin. " + "Vui lòng cập nhật tại PUT /api/econtracts/landlord-profiles/me."));

        Map<String, Object> data = new HashMap<>();
        data.put("LANDLORD_NAME", lp.getFullName());
        data.put("LANDLORD_ID", lp.getIdentityNumber());
        data.put("LANDLORD_ID_ISSUE", nvl(lp.getIdentityIssueDate(), "") + " " + nvl(lp.getIdentityIssuePlace(), ""));
        data.put("LANDLORD_ADDRESS", nvl(lp.getAddress(), ""));
        data.put("LANDLORD_PHONE", nvl(lp.getPhoneNumber(), ""));
        data.put("LANDLORD_EMAIL", lp.getEmail());
        data.put("LANDLORD_BANK", nvl(lp.getBankAccount(), ""));
        data.put("TENANT_NAME", req.name());
        data.put("TENANT_ID", nvl(req.identityNumber(), ""));
        data.put("TENANT_ID_ISSUE", req.dateOfIssue() != null
                ? DMY.format(req.dateOfIssue()) + " " + nvl(req.placeOfIssue(), "") : "");
        data.put("TENANT_ADDRESS", nvl(req.tenantAddress(), ""));
        data.put("TENANT_PHONE", nvl(req.phoneNumber(), ""));
        data.put("TENANT_EMAIL", req.email());
        data.put("PROPERTY_ADDRESS", house.getAddress());
        data.put("AREA", req.areaOrDefault());
        data.put("STRUCTURE", req.structureOrDefault());
        data.put("PURPOSE", req.purposeOrDefault());
        data.put("OWNERSHIP_DOCS", req.ownershipDocsOrDefault());
        data.put("START_DATE", DMY.format(req.startDate()));
        data.put("END_DATE", DMY.format(req.endDate()));
        data.put("RENEW_NOTICE_DAYS", req.renewNoticeDaysOrDefault());
        data.put("RENT_AMOUNT", req.rentAmount());
        data.put("RENT_TEXT", NumberToTextConverter.convert(req.rentAmount()));
        data.put("TAX_FEE_NOTE", req.taxFeeNoteOrDefault());
        data.put("PAY_CYCLE", req.payCycleOrDefault());
        data.put("PAY_DAY", req.payDate());
        data.put("LATE_DAYS", req.lateDaysOrDefault());
        data.put("LATE_PENALTY", req.latePenaltyPercentOrDefault());
        data.put("DEPOSIT_AMOUNT", req.depositAmount());
        data.put("DEPOSIT_DATE", DMY.format(req.depositDate()));
        data.put("DEPOSIT_REFUND_DAYS", req.depositRefundDaysOrDefault());
        data.put("HANDOVER_DATE", DMY.format(req.handoverDate()));
        data.put("ASSETS_TABLE", buildAssetTable(req.houseId()));
        data.put("UTILITY_RULES", "Theo thỏa thuận hai bên");
        data.put("LANDLORD_NOTICE_DAYS", req.landlordNoticeDaysOrDefault());
        data.put("CURE_DAYS", req.cureDaysOrDefault());
        data.put("MAX_LATE_DAYS", req.maxLateDaysOrDefault());
        data.put("EARLY_TERMINATION_PENALTY", req.earlyTerminationPenaltyOrDefault());
        data.put("LANDLORD_BREACH_COMPENSATION", req.landlordBreachCompensationOrDefault());
        data.put("FORCE_MAJEURE_NOTICE_HOURS", req.forceMajeureNoticeHoursOrDefault());
        data.put("DISPUTE_DAYS", req.disputeDaysOrDefault());
        data.put("DISPUTE_FORUM", req.disputeForumOrDefault());
        data.put("COPIES", req.copiesOrDefault());
        data.put("EACH_KEEP", req.eachKeepOrDefault());
        data.put("EFFECTIVE_DATE", DMY.format(Instant.now()));

        EContract e = EContract.builder()
                .userId(tenantId).houseId(req.houseId())
                .startAt(req.startDate()).endAt(req.endDate())
                .name("EContract_" + req.name().trim() + "_" + System.currentTimeMillis())
                .html(renderPlaceholders(tmpl.getContentHtml(), data))
                .status(EContractStatus.DRAFT)
                .price(req.rentAmount()).depositAmount(req.depositAmount())
                .payDate(req.payDate()).lateDays(req.lateDaysOrDefault())
                .latePenaltyPercent(req.latePenaltyPercentOrDefault())
                .depositRefundDays(req.depositRefundDaysOrDefault())
                .handoverDate(req.handoverDate())
                .tenantIdentityNumber(req.identityNumber())
                .tenantName(req.name()).createdBy(actorId).build();
        contractRepo.save(e);
        log.info("[EContract] Created DRAFT contractId={}", e.getId());
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
            throw new IllegalStateException("Render PDF thất bại: " + e.getMessage(), e);
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
        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"width:100%;border-collapse:collapse;\">")
                .append("<thead><tr>")
                .append("<th style=\"border:1px solid #000;padding:6px;width:10%;\">STT</th>")
                .append("<th style=\"border:1px solid #000;padding:6px;\">Tên tài sản</th>")
                .append("<th style=\"border:1px solid #000;padding:6px;width:15%;\">Số lượng</th>")
                .append("</tr></thead><tbody>");
        int i = 1;
        for (AssetItemDto item : assetGrpc.getAssetItemsByHouseId(houseId)) {
            sb.append("<tr>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">").append(i++).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;\">")
                    .append(HtmlUtils.htmlEscape(item.getDisplayName())).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">1</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String renderPlaceholders(String tmpl, Map<String, Object> data) {
        Matcher m = Pattern.compile("\\{\\{\\s*([A-Z0-9_]+)\\s*}}").matcher(tmpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = data.get(m.group(1));
            if (v == null) throw new IllegalStateException("Missing placeholder: " + m.group(1));
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

    private void sendContractCompletedEvent(EContract contract) {
        try {
            String tenantEmail = null;
            Boolean isNewAccount = null;
            try {
                UserResponse tenant = userGrpc.getUserById(contract.getUserId().toString());
                if (tenant != null && !tenant.getEmail().isBlank()) {
                    tenantEmail = tenant.getEmail();
                    isNewAccount = !tenant.getIsEnabled();
                }
            } catch (Exception e) {
                log.warn("[EContract] Cannot fetch tenant email userId={}: {}", contract.getUserId(), e.getMessage());
            }

            ContractCompletedEvent event = ContractCompletedEvent.builder()
                    .contractId(contract.getId())
                    .tenantId(contract.getUserId())
                    .tenantEmail(tenantEmail)
                    .isNewAccount(isNewAccount)
                    .houseId(contract.getHouseId())
                    .landlordId(contract.getCreatedBy())
                    .depositAmount(contract.getDepositAmount())
                    .rentAmount(contract.getPrice())
                    .payDate(contract.getPayDate())
                    .startAt(contract.getStartAt())
                    .endAt(contract.getEndAt())
                    .completedAt(Instant.now())
                    .build();

            kafka.send("contract-completed-topic", event)
                    .whenComplete((res, ex) -> {
                        if (ex != null)
                            log.error("[EContract] ContractCompleted Kafka FAILED contractId={}: {}", contract.getId(), ex.getMessage(), ex);
                        else
                            log.info("[EContract] ContractCompleted Kafka OK contractId={} offset={}", contract.getId(), res.getRecordMetadata().offset());
                    });
        } catch (Exception e) {
            log.error("[EContract] sendContractCompletedEvent failed contractId={}: {}", contract.getId(), e.getMessage(), e);
        }
    }

    private PageResponse<EContractDto> loadPage(PageRequest request) {
        var pageable = org.springframework.data.domain.PageRequest.of(
                request.page(),
                request.validSize(),
                Sort.by(request.sortBy()).descending());

        Page<EContract> result = contractRepo.findAll(pageable);

        return PageResponse.of(
                mapper.contractsToDtoList(result.getContent()),
                result.hasNext(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber()
        );
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

    private EContract findById(UUID id) {
        return contractRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + id));
    }

    private EContract findByDocumentId(String documentId) {
        return contractRepo.findByDocumentId(documentId)
                .orElseThrow(() -> new NotFoundException("Contract not found for documentId: " + documentId));
    }
}