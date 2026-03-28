package com.isums.contractservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.assetservice.grpc.AssetItemDto;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.domains.entities.*;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.*;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.*;
import com.isums.contractservice.infrastructures.grpcs.*;
import com.isums.contractservice.infrastructures.mappers.EContractMapper;
import com.isums.contractservice.infrastructures.repositories.*;
import com.isums.contractservice.utils.NumberToTextConverter;
import com.isums.houseservice.grpc.HouseResponse;
import com.isums.userservice.grpc.UserResponse;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import common.statics.Roles;
import jakarta.ws.rs.ForbiddenException;
import lombok.NonNull;
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
import org.springframework.cache.*;
import org.springframework.cache.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.*;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EContractServiceImpl implements EContractService {

    private final VnptEContractClient econtractClient;
    private final VnptEContractClient vnptEContractClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final HouseGrpcClient houseGrpcClient;
    private final UserGrpcClient userGrpcClient;
    private final AssetGrpcClient assetGrpcClient;
    private final EContractRepository eContractRepository;
    private final EContractTemplateRepository templateRepository;
    private final LandlordProfileRepository landlordProfileRepository;
    private final EContractMapper eContractMapper;
    private final S3Service s3Service;
    private final CacheManager cacheManager;
    private final ObjectMapper mapper;
    private final KeycloakAdminServiceImpl keycloakAdminService;
    private final S3Client s3Client;

    @Value("${app.s3.bucket}")
    private String bucket;

    @Value("${ocr.service.url}")
    private String ocrServiceUrl;

    private final DateTimeFormatter dayMonthYear =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);


    private record CccdOcrResult(
            String identityNumber,
            String fullName,
            String dateOfBirth,
            String gender,
            String placeOfOrigin,
            String address,
            String issueDate,
            String issuePlace
    ) {
        static CccdOcrResult from(JsonNode node) {
            return new CccdOcrResult(
                    nullableText(node, "identityNumber"),
                    nullableText(node, "fullName"),
                    nullableText(node, "dateOfBirth"),
                    nullableText(node, "gender"),
                    nullableText(node, "placeOfOrigin"),
                    nullableText(node, "address"),
                    nullableText(node, "issueDate"),
                    nullableText(node, "issuePlace")
            );
        }

        private static String nullableText(JsonNode n, String field) {
            JsonNode v = n.path(field);
            return (v.isTextual() && !v.asText().isBlank()) ? v.asText().trim() : null;
        }
    }

    @Override
    @Transactional
    @CacheEvict(allEntries = true, value = "allEContracts")
    public EContractDto createDraftEContract(UUID actorId, String jwtToken,
                                             CreateEContractRequest req) {
        try {
            UUID primaryTenantUserId;

            if (!req.isNewAccount()) {
                UserResponse res = userGrpcClient.getUserByEmail(req.email(), jwtToken);
                primaryTenantUserId = UUID.fromString(res.getId());
            } else {
                primaryTenantUserId = UUID.randomUUID();

                String tokenVnpt = econtractClient.getToken();
                createVnptUser(tokenVnpt, primaryTenantUserId, req);

                kafkaTemplate.send("createUser-topic", CreateUserPlacedEvent.builder()
                        .id(primaryTenantUserId)
                        .name(req.name())
                        .email(req.email())
                        .phoneNumber(req.phoneNumber())
                        .identityNumber(req.identityNumber())
                        .isEnabled(false)
                        .build());
            }

            HouseResponse house = houseGrpcClient.getHouseById(req.houseId());
            if (house == null)
                throw new NotFoundException("House not found: " + req.houseId());

            return createDocument(actorId, primaryTenantUserId, req, house);

        } catch (Exception ex) {
            log.error("createDraftEContract failed", ex);
            throw new IllegalStateException("createDraftEContract failed: " + ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCccd(UUID contractId) {
        return eContractRepository.findById(contractId)
                .map(c -> c.getCccdFrontKey() != null && c.getCccdBackKey() != null)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
    }

    @Override
    @Transactional
    public void uploadCccd(String documentId, MultipartFile frontImage, MultipartFile backImage) {
        EContract contract = eContractRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + documentId));

        if (contract.getStatus() != EContractStatus.CONFIRM_BY_TENANT) {
            throw new IllegalStateException("Cannot upload CCCD in status: " + contract.getStatus());
        }

        validateBasic(frontImage, "mặt trước");
        validateBasic(backImage, "mặt sau");

        CccdOcrResult ocrFront = callOcrFrontAndValidate(
                frontImage,
                contract.getTenantIdentityNumber(),
                contract.getTenantName()
        );

        validateCccdBack(backImage);

        deleteCccdIfExists(contract.getCccdFrontKey());
        deleteCccdIfExists(contract.getCccdBackKey());

        String frontKey = s3Service.uploadCccdImage(frontImage, contract.getId(), "mat-truoc");
        String backKey = s3Service.uploadCccdImage(backImage, contract.getId(), "mat-sau");

        contract.setCccdFrontKey(frontKey);
        contract.setCccdBackKey(backKey);
        contract.setCccdVerifiedAt(Instant.now());

        eContractRepository.save(contract);
        log.info("[CCCD] Uploaded & verified contractId={} id={} name={}",
                contract.getId(),
                ocrFront != null ? ocrFront.identityNumber() : "skipped",
                ocrFront != null ? ocrFront.fullName() : "skipped");
    }

    @Override
    @Cacheable(value = "allEContracts")
    public VnptDocumentDto readyEContract(UUID contractId) {
        try {
            EContract eContract = eContractRepository.findById(contractId)
                    .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));

            return readyEContract(eContract);
        } catch (Exception ex) {
            log.error("readyEContract failed", ex);
            throw new IllegalStateException("readyEContract failed: " + ex.getMessage());
        }
    }

    @Override
    @Transactional
    @CacheEvict(allEntries = true, value = "allEContracts")
    public void confirmEContract(UUID contractId, String keycloakId, String jwtToken) {
        EContract eContract = eContractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));

        eContract.getStatus().validateTransition(EContractStatus.CONFIRM_BY_LANDLORD);

        var role = userGrpcClient.getUserRoles(keycloakId, jwtToken);
        if (!role.getRolesList().contains(Roles.LANDLORD)) {
            throw new ForbiddenException("User is not landlord");
        }

        eContract.setStatus(EContractStatus.CONFIRM_BY_LANDLORD);
        eContractRepository.save(eContract);
        log.info("[EContract] CONFIRM_BY_LANDLORD contractId={}", contractId);
    }

    @Override
    public ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode) {
        try {
            String body = vnptEContractClient.getAccessInfoByProcessCode(processCode);
            var processLogin = parseProcessLogin(body);

            EContract eContract = eContractRepository
                    .findByDocumentId(processLogin.documentId())
                    .orElseThrow(() -> new NotFoundException("EContract not found"));

            eContract.setStatus(EContractStatus.CONFIRM_BY_TENANT);
            eContractRepository.save(eContract);

            log.info("[EContract] CONFIRM_BY_TENANT contractId={}", eContract.getId());
            return parseProcessLogin(body);

        } catch (Exception ex) {
            log.error("getAccessInfoByProcessCode failed", ex);
            throw new IllegalStateException("getAccessInfoByProcessCode failed", ex);
        }
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "allEContracts", allEntries = true),
            @CacheEvict(cacheNames = "vnptProcessCode", key = "#process.processCode", allEntries = true)
    })
    public ProcessResponse signProcess(VnptProcessDto process) {
        try {
            var processResponse = vnptEContractClient.signProcess(process);

            if (processResponse == null)
                throw new IllegalStateException("VNPT signProcess returned null");
            if (processResponse.getData() == null)
                throw new IllegalStateException("VNPT signProcess failed: " + processResponse.getMessage());

            if (processResponse.getSuccess() && processResponse.getData().id() != null) {
                var eContract = eContractRepository
                        .findByDocumentId(String.valueOf(processResponse.getData().id()))
                        .orElseThrow(() -> new NotFoundException(
                                "EContract not found for documentId: " + processResponse.getData().id()));

                eContract.getStatus().validateTransition(EContractStatus.COMPLETED);
                eContract.setStatus(EContractStatus.COMPLETED);
                eContractRepository.save(eContract);

                log.info("[EContract] COMPLETED contractId={}", eContract.getId());

                activateTenantIfNeeded(eContract.getUserId());
                mapUserToHouse(eContract.getUserId(), eContract.getHouseId());
            }

            return processResponse.getData();

        } catch (Exception ex) {
            log.error("signProcess failed", ex);
            throw new IllegalStateException("signProcess failed");
        }
    }

    @Override
    public ProcessResponse signProcessForAdmin(VnptProcessDto process) {
        try {
            VnptProcessDto updatedProcess = process.withToken(vnptEContractClient.getToken());
            var processResponse = vnptEContractClient.signProcess(updatedProcess);

            if (processResponse == null)
                throw new IllegalStateException("VNPT signProcess returned null");
            if (processResponse.getData() == null)
                throw new IllegalStateException("VNPT signProcess failed: " + processResponse.getMessage());

            if (processResponse.getSuccess() && processResponse.getData().id() != null) {
                var eContract = eContractRepository
                        .findByDocumentId(String.valueOf(processResponse.getData().id()))
                        .orElseThrow(() -> new NotFoundException(
                                "EContract not found for documentId: " + processResponse.getData().id()));

                eContract.getStatus().validateTransition(EContractStatus.IN_PROGRESS);
                eContract.setStatus(EContractStatus.IN_PROGRESS);
                eContractRepository.save(eContract);

                log.info("[EContract] IN_PROGRESS contractId={}", eContract.getId());
            }

            return processResponse.getData();

        } catch (Exception ex) {
            log.error("signProcessForAdmin failed", ex);
            throw ex;
        }
    }

    @Override
    @Transactional
    @CacheEvict(allEntries = true, value = "allEContracts")
    public void terminateContract(UUID contractId, String reason, UUID terminatedBy) {
        EContract contract = eContractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));

        contract.getStatus().validateTransition(EContractStatus.CANCELLED);
        contract.setStatus(EContractStatus.CANCELLED);
        contract.setTerminatedAt(Instant.now());
        contract.setTerminatedReason(reason);
        contract.setTerminatedBy(terminatedBy);
        eContractRepository.save(contract);

        log.info("[EContract] CANCELLED contractId={} by={}", contractId, terminatedBy);
    }

    @Override
    public EContractDto getEContractById(UUID id) {
        try {
            return eContractMapper.contractToDto(
                    eContractRepository.findById(id)
                            .orElseThrow(() -> new NotFoundException("Contract not found: " + id)));
        } catch (Exception ex) {
            log.error("getEContractById failed", ex);
            throw new IllegalStateException("getEContractById failed");
        }
    }

    @Override
    @Cacheable(value = "allEContracts")
    public List<EContractDto> getAllEContracts() {
        try {
            return eContractMapper.contractsToDtoList(
                    eContractRepository.findAllByOrderByCreatedAtAsc());
        } catch (Exception ex) {
            log.error("getAllEContracts failed", ex);
            throw new IllegalStateException("getAllEContracts failed");
        }
    }

    @Override
    @CacheEvict(allEntries = true, value = "allEContracts")
    public EContractDto updateEContractById(UUID id, UpdateEContractRequest req) {
        try {
            EContract econtract = eContractRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Contract not found: " + id));

            if (econtract.getStatus() != EContractStatus.DRAFT
                    && econtract.getStatus() != EContractStatus.READY) {
                throw new IllegalStateException("Cannot update contract in status: " + econtract.getStatus());
            }

            eContractMapper.patch(req, econtract);
            eContractRepository.save(econtract);
            return eContractMapper.contractToDto(econtract);

        } catch (Exception ex) {
            log.error("updateEContractById failed", ex);
            throw new IllegalStateException("updateEContractById failed");
        }
    }

    @Override
    @Cacheable(cacheNames = "vnptDocumentById", key = "#documentId")
    public VnptDocumentDto getVnptEContractByDocumentId(String documentId) {
        String token = vnptEContractClient.getToken();
        var eContract = vnptEContractClient.getEContractById(documentId, token);
        if (eContract.getData().id() == null)
            throw new NotFoundException("EContract not found: " + documentId);
        return eContract.getData();
    }

    @Override
    public EContractDto getEContractOutSystem(String processCode) {
        try {
            var vnptDocument = getAccessInfoByProcessCode(processCode);
            EContract eContract = eContractRepository
                    .findByDocumentNo(vnptDocument.documentNo())
                    .orElseThrow(() -> new NotFoundException("EContract not found"));
            return eContractMapper.contractToDto(eContract);
        } catch (Exception ex) {
            log.error("getEContractOutSystem failed", ex);
            throw new IllegalStateException("getEContractOutSystem failed");
        }
    }

    private CccdOcrResult callOcrFrontAndValidate(MultipartFile file,
                                                  String expectedId,
                                                  String expectedName) {
        try {
            JsonNode node = callOcrService(file, "/ocr/cccd");
            CccdOcrResult result = CccdOcrResult.from(node);

            boolean isFront = node.path("isFrontSide").asBoolean(false);
            if (!isFront) {
                int score = node.path("frontScore").asInt(0);
                throw new IllegalArgumentException(
                        "Ảnh mặt trước CCCD không hợp lệ (score=" + score + "). "
                                + "Vui lòng chụp đúng mặt trước của thẻ căn cước.");
            }

            if (result.identityNumber() == null) {
                throw new IllegalArgumentException(
                        "Không tìm thấy số CCCD trong ảnh mặt trước. Vui lòng chụp lại rõ hơn.");
            }
            if (expectedId != null && !expectedId.isBlank()
                    && !result.identityNumber().equals(expectedId)) {
                throw new IllegalArgumentException(
                        "Số CCCD trong ảnh (" + result.identityNumber() + ") "
                                + "không khớp với hợp đồng (" + expectedId + ").");
            }

            if (result.fullName() != null && !result.fullName().isBlank()) {
                if (expectedName != null && !expectedName.isBlank()) {
                    String normOcr = normalize(result.fullName());
                    String normExpected = normalize(expectedName);
                    if (!normOcr.equals(normExpected)) {
                        throw new IllegalArgumentException(
                                "Tên trong ảnh CCCD (" + result.fullName() + ") "
                                        + "không khớp với hợp đồng (" + expectedName + ").");
                    }
                }
            } else {
                // OCR không đọc được tên → chỉ warn, không block
                log.warn("[CCCD] Không đọc được tên mặt trước, bỏ qua kiểm tra tên. id={}",
                        result.identityNumber());
            }

            log.info("[CCCD] Front OK id={} name={}", result.identityNumber(), result.fullName());
            return result;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[CCCD] Front OCR service lỗi, bỏ qua validate: {}", e.getMessage());
            return null;
        }
    }

    private void validateCccdBack(MultipartFile file) {
        try {
            JsonNode node = callOcrService(file, "/ocr/cccd/back");

            boolean isReadable = node.path("isReadable").asBoolean(true);
            if (!isReadable) {
                throw new IllegalArgumentException(
                        "Ảnh mặt sau CCCD không rõ nét. Vui lòng chụp lại.");
            }

            boolean isBack = node.path("isBackSide").asBoolean(false);
            if (!isBack) {
                int score = node.path("backScore").asInt(0);
                throw new IllegalArgumentException(
                        "Ảnh mặt sau CCCD không hợp lệ (score=" + score + "). "
                                + "Vui lòng chụp đúng mặt sau của thẻ căn cước.");
            }

            String issueDate = node.path("issueDate").asText(null);
            log.info("[CCCD] Back OK issueDate={}", issueDate);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[CCCD] Back OCR service lỗi, bỏ qua validate: {}", e.getMessage());
        }
    }

    private JsonNode callOcrService(MultipartFile file, String path) throws Exception {
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", resource);

        String raw = RestClient.create().post()
                .uri(ocrServiceUrl + path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);

        return mapper.readTree(raw);
    }

    private String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase();
        String nfd = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void validateBasic(MultipartFile file, String side) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Ảnh " + side + " không được để trống");

        String mime = file.getContentType();
        if (mime == null || !mime.startsWith("image/"))
            throw new IllegalArgumentException("File " + side + " phải là ảnh (jpg, png, ...)");

        if (file.getSize() < 50 * 1024)
            throw new IllegalArgumentException("Ảnh " + side + " quá nhỏ, vui lòng chụp lại rõ hơn");

        if (file.getSize() > 10 * 1024 * 1024)
            throw new IllegalArgumentException("Ảnh " + side + " quá lớn (tối đa 10MB)");
    }

    private void deleteCccdIfExists(String key) {
        if (key == null) return;
        try {
            s3Client.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (Exception e) {
            log.warn("[CCCD] Delete old key failed key={}: {}", key, e.getMessage());
        }
    }

    private VnptDocumentDto readyEContract(EContract eContract) {
        try {
            byte[] pdfBytes = renderHtmlToPdf(eContract.getHtml());

            Map<String, AnchorBoxVnpt> anchors = findAnchors(pdfBytes, List.of("SIGN_A", "SIGN_B"));
            VnptPosition positionA = getVnptEContractPosition(
                    pdfBytes, anchors.get("SIGN_A"), 170, 90, 60, 18, -28, 35, 20, 60);
            VnptPosition positionB = getVnptEContractPosition(
                    pdfBytes, anchors.get("SIGN_B"), 170, 90, 60, 18, 0, 35, 20, 60);

            String No = "EC_" + Instant.now().getEpochSecond();
            FileInfoDto fileInfoDto = new FileInfoDto(null, pdfBytes, No + ".pdf");
            CreateDocumentDto createDocumentDto = new CreateDocumentDto(
                    fileInfoDto, "Rental EContract", "Rental EContract", 3059, 3110, No);

            String tokenVnpt = econtractClient.getToken();
            VnptResult<VnptDocumentDto> result = econtractClient.createDocument(tokenVnpt, createDocumentDto);

            if (result == null || result.getData() == null) {
                String errorMsg = result != null ? result.getMessage() : "Unknown error";
                throw new IllegalStateException("Failed to create document on VNPT: " + errorMsg);
            }

            String documentId = result.getData().id();

            eContract.getStatus().validateTransition(EContractStatus.READY);
            eContract.setStatus(EContractStatus.READY);
            eContract.setDocumentId(documentId);
            eContract.setDocumentNo(result.getData().no());
            eContractRepository.save(eContract);

            String vnptToken = vnptEContractClient.getToken();
            String userCodeSecond = eContract.getUserId().toString();
            String userCodeFirst = "hoangtuzami";

            updateProcess(vnptToken, documentId,
                    userCodeFirst, userCodeSecond,
                    positionA.pos(), positionB.pos(), positionA.pageSign());

            return econtractClient.sendProcess(vnptToken, documentId).getData();

        } catch (Exception ex) {
            log.error("readyEContract failed", ex);
            throw new IllegalStateException("readyEContract failed: " + ex.getMessage());
        }
    }

    private EContractDto createDocument(UUID actorId, UUID primaryTenantUserId,
                                        CreateEContractRequest req,
                                        HouseResponse house) {
        EContractTemplate template = templateRepository.findByCode("LEASE_HOUSE")
                .orElseThrow(() -> new IllegalStateException("Template LEASE_HOUSE not found"));

        LandlordProfile landlord = landlordProfileRepository.findByUserId(actorId)
                .orElseThrow(() -> new IllegalStateException(
                        "Landlord chưa cập nhật thông tin pháp lý. "
                                + "Vui lòng cập nhật tại PUT /api/landlord-profiles/me trước khi tạo hợp đồng."));

        Map<String, Object> data = new HashMap<>();

        data.put("LANDLORD_NAME", landlord.getFullName());
        data.put("LANDLORD_ID", landlord.getIdentityNumber());
        data.put("LANDLORD_ID_ISSUE",
                nvl(landlord.getIdentityIssueDate(), "") + " " + nvl(landlord.getIdentityIssuePlace(), ""));
        data.put("LANDLORD_ADDRESS", nvl(landlord.getAddress(), ""));
        data.put("LANDLORD_PHONE", nvl(landlord.getPhoneNumber(), ""));
        data.put("LANDLORD_EMAIL", landlord.getEmail());
        data.put("LANDLORD_BANK", nvl(landlord.getBankAccount(), ""));

        data.put("TENANT_NAME", req.name());
        data.put("TENANT_ID", nvl(req.identityNumber(), ""));
        data.put("TENANT_ID_ISSUE", req.dateOfIssue() != null
                ? dayMonthYear.format(req.dateOfIssue()) + " " + nvl(req.placeOfIssue(), "")
                : "");
        data.put("TENANT_ADDRESS", nvl(req.tenantAddress(), ""));
        data.put("TENANT_PHONE", nvl(req.phoneNumber(), ""));
        data.put("TENANT_EMAIL", req.email());

        data.put("PROPERTY_ADDRESS", house.getAddress());
        data.put("AREA", req.areaOrDefault());
        data.put("STRUCTURE", req.structureOrDefault());
        data.put("PURPOSE", req.purposeOrDefault());
        data.put("OWNERSHIP_DOCS", req.ownershipDocsOrDefault());

        data.put("START_DATE", dayMonthYear.format(req.startDate()));
        data.put("END_DATE", dayMonthYear.format(req.endDate()));
        data.put("RENEW_NOTICE_DAYS", req.renewNoticeDaysOrDefault());

        data.put("RENT_AMOUNT", req.rentAmount());
        data.put("RENT_TEXT", NumberToTextConverter.convert(req.rentAmount()));
        data.put("TAX_FEE_NOTE", req.taxFeeNoteOrDefault());
        data.put("PAY_CYCLE", req.payCycleOrDefault());
        data.put("PAY_DAY", req.payDate());
        data.put("LATE_DAYS", req.lateDaysOrDefault());
        data.put("LATE_PENALTY", req.latePenaltyPercentOrDefault());

        data.put("DEPOSIT_AMOUNT", req.depositAmount());
        data.put("DEPOSIT_DATE", dayMonthYear.format(req.depositDate()));
        data.put("DEPOSIT_REFUND_DAYS", req.depositRefundDaysOrDefault());

        data.put("HANDOVER_DATE", dayMonthYear.format(req.handoverDate()));
        data.put("ASSETS_TABLE", createTableInDocumentVN(req.houseId()));

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
        data.put("EFFECTIVE_DATE", dayMonthYear.format(Instant.now()));

        String html = placeholderEngine(template.getContentHtml(), data);
        String contractName = "EContract_" + req.name().trim() + "_" + Instant.now().getEpochSecond();

        EContract econtract = EContract.builder()
                .userId(primaryTenantUserId)
                .houseId(req.houseId())
                .startAt(req.startDate())
                .endAt(req.endDate())
                .name(contractName)
                .html(html)
                .status(EContractStatus.DRAFT)
                .price(req.rentAmount())
                .depositAmount(req.depositAmount())
                .payDate(req.payDate())
                .lateDays(req.lateDaysOrDefault())
                .latePenaltyPercent(req.latePenaltyPercentOrDefault())
                .depositRefundDays(req.depositRefundDaysOrDefault())
                .handoverDate(req.handoverDate())
                .tenantIdentityNumber(req.identityNumber())
                .tenantName(req.name())
                .createdBy(actorId)
                .build();

        eContractRepository.save(econtract);
        log.info("[EContract] Created DRAFT contractId={} tenant={} house={}",
                econtract.getId(), primaryTenantUserId, req.houseId());

        return eContractMapper.contractToDto(econtract);
    }

    private void activateTenantIfNeeded(UUID userId) {
        try {
            UserResponse user = userGrpcClient.getUserById(userId.toString());
            if (user == null) {
                log.warn("activateTenant: user not found userId={}", userId);
                return;
            }
            if (user.getIsEnabled()) {
                log.info("activateTenant: already enabled userId={} → skip", userId);
                return;
            }

            String tempPassword = keycloakAdminService.activateUser(user.getKeycloakId());
            kafkaTemplate.send("user-activated-topic", UserActivatedEvent.builder()
                    .userId(userId)
                    .email(user.getEmail())
                    .name(user.getName())
                    .tempPassword(tempPassword)
                    .build());

            log.info("activateTenant: sent activation event userId={} email={}", userId, user.getEmail());
        } catch (Exception e) {
            log.error("activateTenant failed userId={} — contract still completed", userId, e);
        }
    }

    private void mapUserToHouse(UUID userId, UUID houseId) {
        try {
            kafkaTemplate.send("map-user-to-house-topic", MapUserToHouseEvent.builder()
                    .userId(userId)
                    .houseId(houseId)
                    .build());
            log.info("mapUserToHouse: sent event userId={} houseId={}", userId, houseId);
        } catch (Exception e) {
            log.error("mapUserToHouse failed userId={} houseId={}", userId, houseId, e);
        }
    }

    private void createVnptUser(String token, UUID primaryTenantUserId,
                                CreateEContractRequest req) {
        VnptUserUpsert vnptUser = getVnptUserUpsert(primaryTenantUserId, req);
        VnptResult<List<VnptUserDto>> userResult = econtractClient.CreateOrUpdateUser(token, vnptUser);

        if (userResult == null || userResult.getData() == null) {
            String errorMsg = userResult != null ? userResult.getMessage() : "Unknown error";
            log.error("Failed to create user on VNPT: {}", errorMsg);
            throw new IllegalStateException("Failed to create user on VNPT: " + errorMsg);
        }
    }

    private @NonNull VnptUserUpsert getVnptUserUpsert(UUID primaryTenantUserId,
                                                      CreateEContractRequest req) {
        List<UUID> roleIds = new ArrayList<>(List.of(UUID.fromString("0aa2afc9-39c5-4652-baec-08ddc28cdda2")));
        List<Integer> departmentIds = new ArrayList<>(List.of(3110));

        return new VnptUserUpsert(
                primaryTenantUserId.toString(),
                req.email(), req.name(), req.email(), req.phoneNumber(),
                1, 0, 2,
                true, true,
                1, -1,
                departmentIds, roleIds
        );
    }

    private void updateProcess(String token, String documentId,
                               String userCodeFirst, String userCodeSecond,
                               String positionA, String positionB,
                               int pageSign) {
        List<ProcessesRequestDTO> processes = List.of(
                new ProcessesRequestDTO(1, userCodeFirst, "E", positionA, pageSign),
                new ProcessesRequestDTO(2, userCodeSecond, "E", positionB, pageSign)
        );
        var result = econtractClient.UpdateProcess(token,
                new VnptUpdateProcessDTO(documentId, true, processes));
        log.info("updateProcess result: {}", result);
    }

    private String placeholderEngine(String template, Map<String, Object> data) {
        final Pattern P = Pattern.compile("\\{\\{\\s*([A-Z0-9_]+)\\s*}}");
        Matcher m = P.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object value = data.get(key);
            if (value == null)
                throw new IllegalStateException("Missing placeholder: " + key);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String createTableInDocumentVN(UUID houseId) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("""
                <table style="width: 100%; border-collapse: collapse; margin-top: 6px;">
                <thead>
                <tr>
                    <th style="border: 1px solid #000; padding: 6px; text-align: center; width: 10%;">Số thứ tự</th>
                    <th style="border: 1px solid #000; padding: 6px; text-align: center;">Tên tài sản</th>
                    <th style="border: 1px solid #000; padding: 6px; text-align: center; width: 15%;">Số lượng</th>
                </tr>
                </thead>
                <tbody>
                """);

        int i = 1;
        for (AssetItemDto item : assetGrpcClient.getAssetItemsByHouseId(houseId)) {
            String name = HtmlUtils.htmlEscape(item.getDisplayName());
            sb.append("<tr>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">").append(i).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;\">").append(name).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">1</td>")
                    .append("</tr>");
            i++;
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private byte[] renderHtmlToPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String baseUri = Objects.requireNonNull(getClass().getResource("/")).toExternalForm();
            String xhtml = toXhtml(html, baseUri);

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_1_A);
            builder.useFont(() -> cp("fonts/SVN-Times New Roman 2.ttf"),
                    "Times New Roman", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> cp("fonts/SVN-Times New Roman 2 bold.ttf"),
                    "Times New Roman", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> cp("fonts/SVN-Times New Roman 2 italic.ttf"),
                    "Times New Roman", 400, BaseRendererBuilder.FontStyle.ITALIC, true);
            builder.useFont(() -> cp("fonts/SVN-Times New Roman 2 bold italic.ttf"),
                    "Times New Roman", 700, BaseRendererBuilder.FontStyle.ITALIC, true);
            builder.withHtmlContent(xhtml, baseUri);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Render PDF failed", e);
        }
    }

    private String toXhtml(String html, String baseUri) {
        if (html == null) html = "";
        Document doc = Jsoup.parse(html, baseUri);
        if (doc.head().selectFirst("meta[charset]") == null)
            doc.head().prependElement("meta").attr("charset", "UTF-8");
        if (doc.head().selectFirst("base[href]") == null)
            doc.head().prependElement("base").attr("href", baseUri);
        doc.outputSettings()
                .charset("UTF-8")
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml);
        return doc.html();
    }

    private InputStream cp(String path) {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null)
            throw new IllegalStateException("Missing classpath resource: " + path);
        return in;
    }

    private ProcessLoginInfoDto parseProcessLogin(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.get("data");
            JsonNode dataEl;

            if (data != null && data.isObject()) {
                dataEl = data;
            } else if (root.has("token") && root.has("document")) {
                dataEl = root;
            } else {
                throw new IllegalStateException("Unexpected response format: " + body);
            }

            String accessToken = null;
            JsonNode tokenNode = dataEl.get("token");
            if (tokenNode != null && !tokenNode.isNull()) {
                if (tokenNode.isTextual()) {
                    accessToken = tokenNode.asText();
                } else if (tokenNode.isObject()) {
                    JsonNode at = tokenNode.get("accessToken");
                    if (at != null && at.isTextual()) accessToken = at.asText();
                }
            }

            JsonNode document = dataEl.get("document");
            if (document == null || document.isNull() || !document.isObject())
                throw new IllegalStateException("Missing document: " + body);

            String waitingProcessId = null;
            Integer processedByUserId = null;
            String documentNo = null;
            String documentId = null;
            String position = null;
            Integer pageSign = null;
            boolean isOtp = false;

            JsonNode downId = document.get("id");
            if (downId != null && downId.isTextual()) documentId = downId.asText();

            JsonNode no = document.get("no");
            if (no != null && no.isTextual()) documentNo = no.asText();

            JsonNode waiting = document.get("waitingProcess");
            if (waiting != null && waiting.isObject()) {
                JsonNode id = waiting.get("id");
                if (id != null && id.isTextual()) waitingProcessId = id.asText();

                JsonNode processed = waiting.get("processedByUserId");
                if (processed != null && processed.canConvertToInt())
                    processedByUserId = processed.asInt();

                JsonNode pos = waiting.get("position");
                if (pos != null && pos.isTextual()) position = pos.asText();

                JsonNode ps = waiting.get("pageSign");
                if (ps != null && ps.canConvertToInt()) pageSign = ps.asInt();

                JsonNode accessPermission = waiting.get("accessPermission");
                if (accessPermission != null && accessPermission.isObject()) {
                    JsonNode value = accessPermission.get("value");
                    if (value != null && value.canConvertToInt())
                        isOtp = (value.asInt() == 7);
                }
            }

            return new ProcessLoginInfoDto(
                    waitingProcessId, documentId, documentNo,
                    processedByUserId, accessToken, position, pageSign, isOtp);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot parse response: " + e.getMessage() + "\nRAW=" + body, e);
        }
    }


    private Map<String, AnchorBoxVnpt> findAnchors(byte[] pdfBytes, List<String> anchorTexts) {
        Objects.requireNonNull(pdfBytes, "pdfBytes");
        Objects.requireNonNull(anchorTexts, "anchorTexts");
        if (anchorTexts.isEmpty()) return Collections.emptyMap();

        Set<String> remain = new LinkedHashSet<>(anchorTexts);
        Map<String, AnchorBoxVnpt> found = new HashMap<>();
        int maxAnchorLen = anchorTexts.stream().mapToInt(String::length).max().orElse(32);
        int keepTail = Math.max(256, maxAnchorLen * 8);

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int totalPages = doc.getNumberOfPages();
            for (int pageIdx = 0; pageIdx < totalPages && !remain.isEmpty(); pageIdx++) {
                PDPage page = doc.getPage(pageIdx);
                PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                float pageHeight = box.getHeight();

                AnchorStripper stripper = new AnchorStripper(
                        remain, found, pageHeight, pageIdx + 1, keepTail);
                stripper.setSortByPosition(true);
                stripper.setSuppressDuplicateOverlappingText(false);
                stripper.setStartPage(pageIdx + 1);
                stripper.setEndPage(pageIdx + 1);
                stripper.getText(doc);

                remain.removeAll(found.keySet());
            }

            if (!remain.isEmpty())
                throw new IllegalStateException("Anchors not found in PDF: " + remain);

            return found;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse PDF for anchors", e);
        }
    }

    private static final class AnchorStripper extends PDFTextStripper {
        private final Set<String> remain;
        private final Map<String, AnchorBoxVnpt> found;
        private final float pageHeight;
        private final int pageNo1Based;
        private final int keepTail;
        private final StringBuilder bufText = new StringBuilder(512);
        private final ArrayList<TextPosition> bufPos = new ArrayList<>(512);

        AnchorStripper(Set<String> remain, Map<String, AnchorBoxVnpt> found,
                       float pageHeight, int pageNo1Based, int keepTail) throws IOException {
            super();
            this.remain = remain;
            this.found = found;
            this.pageHeight = pageHeight;
            this.pageNo1Based = pageNo1Based;
            this.keepTail = keepTail;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (remain.isEmpty()) return;

            bufText.append(text);
            bufPos.addAll(positions);

            if (bufText.length() > keepTail * 2) {
                bufText.delete(0, bufText.length() - keepTail);
                if (bufPos.size() > keepTail)
                    bufPos.subList(0, bufPos.size() - keepTail).clear();
            }

            String buf = bufText.toString();
            for (String anchor : new ArrayList<>(remain)) {
                int idx = buf.lastIndexOf(anchor);
                if (idx < 0) continue;

                int startInBuf = buf.length() - bufPos.size();
                int posIdx = idx - startInBuf;
                if (posIdx < 0 || posIdx >= bufPos.size()) continue;

                List<TextPosition> anchorPos =
                        bufPos.subList(posIdx, Math.min(posIdx + anchor.length(), bufPos.size()));
                if (anchorPos.isEmpty()) continue;

                float xMin = Float.MAX_VALUE;
                float yMaxUL = 0f;
                for (TextPosition tp : anchorPos) {
                    float x = tp.getXDirAdj();
                    if (x < xMin) xMin = x;
                    float y = tp.getYDirAdj();
                    float h = tp.getHeightDir();
                    if (y + h > yMaxUL) yMaxUL = y + h;
                }

                found.put(anchor, new AnchorBoxVnpt(pageNo1Based, xMin, pageHeight - yMaxUL));
            }
        }
    }

    public VnptPosition getVnptEContractPosition(byte[] pdfBytes, AnchorBoxVnpt anchor,
                                                 double width, double height,
                                                 double offsetY, double margin,
                                                 double xAdjust, double yAdjust,
                                                 double extraSafeSpace, double topPadding) {
        Objects.requireNonNull(pdfBytes, "pdfBytes must not be null");
        Objects.requireNonNull(anchor, "anchor must not be null");

        if (anchor.page() <= 0) throw new IllegalArgumentException("anchor.page must be > 0");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width/height must be > 0");
        if (margin < 0) throw new IllegalArgumentException("margin must be >= 0");

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int lastPage = doc.getNumberOfPages();
            if (anchor.page() > lastPage)
                throw new IllegalArgumentException(
                        "anchor.page out of range: " + anchor.page() + ", lastPage=" + lastPage);

            PDPage page = doc.getPage(anchor.page() - 1);
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            double pw = box.getWidth();
            double ph = box.getHeight();

            double candidateLly = anchor.bottom() - offsetY + yAdjust - extraSafeSpace;

            if (candidateLly - height < margin) {
                if (anchor.page() >= lastPage) {
                    candidateLly = margin;
                } else {
                    PDPage nextPage = doc.getPage(anchor.page());
                    PDRectangle nb = nextPage.getCropBox() != null
                            ? nextPage.getCropBox() : nextPage.getMediaBox();
                    double npw = nb.getWidth();
                    double nph = nb.getHeight();

                    double llx = clamp(anchor.left() + xAdjust, margin, npw - margin - width);
                    double lly = clamp(nph - margin - height - topPadding + yAdjust,
                            margin, nph - margin - height);

                    return new VnptPosition(buildPos(llx, lly, width, height), anchor.page() + 1);
                }
            }

            double candidateLlx = clamp(anchor.left() + xAdjust, margin, pw - margin - width);
            return new VnptPosition(buildPos(candidateLlx, margin, width, height), anchor.page());

        } catch (IOException e) {
            throw new IllegalStateException("Failed to read PDF bytes", e);
        }
    }

    private double clamp(double v, double min, double max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    private String buildPos(double llx, double lly, double width, double height) {
        int x1 = (int) Math.round(llx);
        int y1 = (int) Math.round(lly);
        int x2 = (int) Math.round(llx + width);
        int y2 = (int) Math.round(lly + height);
        return x1 + "," + y1 + "," + x2 + "," + y2;
    }

    private String nvl(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}