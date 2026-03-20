    package com.isums.contractservice.services;
    
    import com.isums.assetservice.grpc.AssetItemDto;
    import com.isums.contractservice.exceptions.NotFoundException;
    import com.isums.contractservice.infrastructures.abstracts.EContractService;
    import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
    import com.isums.contractservice.domains.dtos.*;
    import com.isums.contractservice.domains.entities.EContract;
    import com.isums.contractservice.domains.entities.EContractTemplate;
    import com.isums.contractservice.domains.enums.EContractStatus;
    import com.isums.contractservice.domains.events.CreateUserPlacedEvent;
    import com.isums.contractservice.infrastructures.grpcs.AssetGrpcClient;
    import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
    import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
    import com.isums.contractservice.infrastructures.mappers.EContractMapper;
    import com.isums.contractservice.infrastructures.repositories.EContractRepository;
    import com.isums.contractservice.infrastructures.repositories.EContractTemplateRepository;
    import com.isums.houseservice.grpc.HouseResponse;
    import com.isums.userservice.grpc.UserResponse;
    import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
    import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
    import common.statics.Roles;
    import jakarta.ws.rs.ForbiddenException;
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
    import org.jspecify.annotations.NonNull;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.cache.Cache;
    import org.springframework.cache.CacheManager;
    import org.springframework.cache.annotation.CacheEvict;
    import org.springframework.cache.annotation.Cacheable;
    import org.springframework.cache.annotation.Caching;
    import org.springframework.cache.interceptor.SimpleKey;
    import org.springframework.kafka.core.KafkaTemplate;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;
    import org.springframework.web.util.HtmlUtils;
    import com.fasterxml.jackson.databind.JsonNode;
    import com.fasterxml.jackson.databind.ObjectMapper;
    
    import java.io.*;
    import java.time.Instant;
    import java.time.ZoneOffset;
    import java.time.format.DateTimeFormatter;
    import java.util.*;
    import java.util.regex.Matcher;
    import java.util.regex.Pattern;
    
    @Service
    @RequiredArgsConstructor
    @Slf4j
    public class EContractServiceImpl implements EContractService {
    
        private final VnptEContractClient econtractClient;
        private final KafkaTemplate<String, Object> kafkaTemplate;
        private final HouseGrpcClient houseGrpcClient;
        private final EContractTemplateRepository templateRepository;
        private final AssetGrpcClient assetGrpcClient;
        private final EContractRepository eContractRepository;
        private final EContractMapper eContractMapper;
        private final VnptEContractClient vnptEContractClient;
        private final CacheManager cacheManager;
        private final UserGrpcClient userGrpcClient;
        private final ObjectMapper mapper;
        private final KeycloakAdminServiceImpl keycloakAdminService;
    
        private final DateTimeFormatter dayMonthYear = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);
    
        @Override
        @CacheEvict(allEntries = true, value = "allEContracts")
        public EContractDto createDraftEContract(UUID actorId, String jwtToken, CreateEContractRequest req) {
            try {
    
                UUID primaryTenantUserId;
                if (!req.isNewAccount()) {
                    String email = req.email();
                    UserResponse res = userGrpcClient.getUserByEmail(email, jwtToken); //double-check
                    primaryTenantUserId = UUID.fromString(res.getId());
    
                } else {
                    primaryTenantUserId = UUID.randomUUID();
    
                    String tokenVnpt = econtractClient.getToken();
                    createVnptUser(tokenVnpt, primaryTenantUserId, req);
    
                    CreateUserPlacedEvent userEvent = CreateUserPlacedEvent.builder()
                            .id(primaryTenantUserId)
                            .name(req.name())
                            .email(req.email())
                            .phoneNumber(req.phoneNumber())
                            .identityNumber(req.identityNumber())
                            .isEnabled(false)
                            .build();
    
    
                    kafkaTemplate.send("createUser-topic", userEvent);
                    System.out.println("Kafka is sent " + userEvent);
    
                }
    
                HouseResponse house = houseGrpcClient.getHouseById(req.houseId());
                if (house == null) {
                    throw new NotFoundException("House with id " + req.houseId() + " not found");
                }
    
                System.out.println("House is found " + house);
    
                return createDocument(actorId, primaryTenantUserId, req, house);
            } catch (Exception ex) {
                log.error("CreateDraftVnptEContract failed", ex);
                throw new IllegalStateException("CreateDraftVnptEContract failed");
            }
        }
    
        private void createVnptUser(String token, UUID primaryTenantUserId, CreateEContractRequest req) {
    
            VnptUserUpsert vnptUser = getVnptUserUpsert(primaryTenantUserId, req);
    
            VnptResult<List<VnptUserDto>> userResult = econtractClient.CreateOrUpdateUser(token, vnptUser);
            if (userResult == null || userResult.getData() == null) {
                String errorMsg = (userResult != null) ? userResult.getMessage() : "Unknown error";
                log.error("Failed to create user on VNPT: {}", errorMsg);
                throw new IllegalStateException("Failed to create user on VNPT: " + errorMsg);
            }
    
        }
    
        private @NonNull VnptUserUpsert getVnptUserUpsert(UUID primaryTenantUserId, CreateEContractRequest req) {
    
            // tam thoi set cung
            List<UUID> roleIds = new ArrayList<UUID>(
                    List.of(UUID.fromString("0aa2afc9-39c5-4652-baec-08ddc28cdda2"))
            );
    
            // tam thoi set cung
            List<Integer> departmentIds = new ArrayList<>(
                    List.of(3110)
            );
    
            return new VnptUserUpsert(
                    primaryTenantUserId.toString(),
                    req.email(),
                    req.name(),
                    req.email(),
                    req.phoneNumber(),
                    1,
                    0,
                    2,
                    true,
                    true,
                    1,
                    -1,
                    departmentIds,
                    roleIds
            );
        }
    
        @Override
        public EContractDto getEContractById(UUID id) {
            try {
                EContract econtract = eContractRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("Contract with id " + id + " not found"));
    
                return eContractMapper.contractToDto(econtract);
            } catch (Exception ex) {
                log.error("getEContractById failed", ex);
                throw new IllegalStateException("getEContractById failed");
            }
        }
    
        @Override
        @Cacheable(value = "allEContracts")
        public List<EContractDto> getAllEContracts() {
            try {
                Object springKey = SimpleKey.EMPTY;
    
                Cache cache = cacheManager.getCache("allEContracts");
                boolean hit = cache != null && cache.get(springKey) != null;
                log.info("allEContracts cache_hit={}", hit);
    
                long t0 = System.nanoTime();
                List<EContract> econtracts = eContractRepository.findAllByOrderByCreatedAtAsc();
                long dbMs = (System.nanoTime() - t0) / 1_000_000;
                log.info("db_findAll_ms={}", dbMs);
    
                return eContractMapper.contractsToDtoList(econtracts);
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
                        .orElseThrow(() -> new IllegalStateException("Contract with id " + id + " not found"));
    
                if (econtract.getStatus() == EContractStatus.DRAFT || econtract.getStatus() == EContractStatus.READY) {
                    eContractMapper.patch(req, econtract);
                    eContractRepository.save(econtract);
                } else {
                    throw new IllegalStateException("Cannot update contract in status: " + econtract.getStatus());
                }
    
                return eContractMapper.contractToDto(econtract);
    
            } catch (Exception ex) {
                log.error("updateEContractById failed", ex);
                throw new IllegalStateException("updateEContractById failed");
            }
        }
    
        @Override
        @CacheEvict(allEntries = true, value = "allEContracts")
        public VnptDocumentDto readyEContract(UUID contractId) {
            try {
                EContract eContract = eContractRepository.findById(contractId)
                        .orElseThrow(() -> new NotFoundException("Contract with id " + contractId + " not found"));
    
                return readyEContract(eContract);
            } catch (Exception ex) {
                log.error("confirmAndSendToTenant failed", ex);
                throw new IllegalStateException("confirmAndSendToTenant failed");
            }
        }
    
        @Override
        @CacheEvict(allEntries = true, value = "allEContracts")
        @Transactional
        public void confirmEContract(UUID contractId, String keycloakId, String jwtToken) {
    
            EContract eContract = eContractRepository.findById(contractId)
                    .orElseThrow(() -> new NotFoundException("Contract with id " + contractId + " not found"));
    
            if (eContract.getStatus() != EContractStatus.READY) {
                throw new IllegalStateException("Cannot confirm contract in status: " + eContract.getStatus());
            }
    
            var role = userGrpcClient.getUserRoles(keycloakId, jwtToken);
    
            if (role.getRolesList().contains(Roles.LANDLORD)) {
                eContract.setStatus(EContractStatus.CONFIRM_BY_LANDLORD);
            } else {
                throw new ForbiddenException("User is not landlord");
            }
            eContractRepository.save(eContract);
    
            log.info("confirmEContract: eContractId={} status={}", eContract.getId(), eContract.getStatus());
    
        }
    
        @Override
        public ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode) {
            try {
    
                String body = vnptEContractClient.getAccessInfoByProcessCode(processCode);
    
                var processLogin = parseProcessLogin(body);
                EContract eContract = eContractRepository.findByDocumentId(processLogin.documentId())
                        .orElseThrow(() -> new NotFoundException("EContract not found"));
    
                eContract.setStatus(EContractStatus.CONFIRM_BY_TENANT);
    
                log.info("getAccessInfoByProcessCode: eContractId={} status={}", eContract.getId(), eContract.getStatus());
                eContractRepository.save(eContract);
    
                return parseProcessLogin(body);
            } catch (Exception ex) {
                log.error("getAccessInfoByProcessCode failed", ex);
                throw new IllegalStateException("getAccessInfoByProcessCode failed", ex);
            }
        }
    
        private VnptDocumentDto readyEContract(EContract eContract) {
            try {
    
                byte[] pdfBytes = renderHtmlToPdf(eContract.getHtml());
    
                Map<String, AnchorBoxVnpt> anchors = findAnchors(pdfBytes, List.of("SIGN_A", "SIGN_B"));
                VnptPosition positionA = getVnptEContractPosition(pdfBytes, anchors.get("SIGN_A"), 170, 90, 60, 18, -28, 35, 20, 60);
                VnptPosition positionB = getVnptEContractPosition(pdfBytes, anchors.get("SIGN_B"), 170, 90, 60, 18, 0, 35, 20, 60);
    
                String No = "EC_" + Instant.now().getEpochSecond();
    
                FileInfoDto fileInfoDto = new FileInfoDto(null, pdfBytes, No + ".pdf");
                CreateDocumentDto createDocumentDto = new CreateDocumentDto(fileInfoDto, "Rental EContract", "Rental EContract", 3059, 3110, No);
    
                String tokenVnpt = econtractClient.getToken();
                VnptResult<VnptDocumentDto> result = econtractClient.createDocument(tokenVnpt, createDocumentDto);
    
                if (result == null || result.getData() == null) {
                    String errorMsg = (result != null) ? result.getMessage() : "Unknown error";
                    log.error("Failed to create document on VNPT: {}", errorMsg);
                    throw new IllegalStateException("Failed to create document on VNPT: " + errorMsg);
                }
    
                String documentId = result.getData().id();
    
                if (eContract.getStatus() != EContractStatus.DRAFT) {
                    throw new IllegalStateException("Cannot ready contract in status: " + eContract.getStatus());
                }
    
                eContract.setStatus(EContractStatus.READY);
                eContract.setDocumentId(documentId);
                eContract.setDocumentNo(result.getData().no());
                eContractRepository.save(eContract);
                String vnptToken = vnptEContractClient.getToken();
                String userCodeSecond = eContract.getUserId().toString();
                String userCodeFirst = "hoangtuzami";
    
                updateProcess(vnptToken, documentId, userCodeFirst, userCodeSecond, positionA.pos(), positionB.pos(), positionA.pageSign());
    
    
                return econtractClient.sendProcess(vnptToken, documentId).getData();
    
            } catch (Exception ex) {
                log.error("readyEContract failed", ex);
                throw new IllegalStateException("readyEContract failed");
            }
        }
    
        @Override
        public EContractDto getEContractOutSystem(String processCode) {
            try {
    
                var vnptDocument = getAccessInfoByProcessCode(processCode);
    
                EContract eContract = eContractRepository.findByDocumentNo(vnptDocument.documentNo())
                        .orElseThrow(() -> new NotFoundException("EContract not found"));
    
                return eContractMapper.contractToDto(eContract);
            } catch (Exception ex) {
                log.error("getEContractByDocumentId failed", ex);
                throw new IllegalStateException("getEContractByDocumentId failed");
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
    
                log.info("VNPT signProcess: success={} message={} dataNull={}",
                        processResponse == null ? null : processResponse.getSuccess(),
                        processResponse == null ? null : processResponse.getMessage(),
                        processResponse == null || processResponse.getData() == null);
    
                if (processResponse == null) {
                    throw new IllegalStateException("VNPT signProcess returned null");
                }
    
                if (processResponse.getData() == null) {
                    log.error("VNPT signProcess failed payload={}", processResponse);
                    throw new IllegalStateException("VNPT signProcess failed: " + processResponse.getMessage());
                }
    
                if (processResponse.getSuccess() && processResponse.getData().id() != null) {
                    log.info("VNPT signProcess success: data={}", processResponse.getData());
                    var eContract = eContractRepository.findByDocumentId(String.valueOf(processResponse.getData().id()))
                            .orElseThrow(() -> new NotFoundException("EContract not found for documentId: " + processResponse.getData().id()));
                    eContract.setStatus(EContractStatus.COMPLETED);
                    eContractRepository.save(eContract);
    
                    log.info("signProcess COMPLETED contractId={} userId={}", eContract.getId(), eContract.getUserId());
    
                    activateTenantIfNeeded(eContract.getUserId());
                }
    
                return processResponse.getData();
    
            } catch (Exception ex) {
                log.error("signProcess failed", ex);
                throw new IllegalStateException("signProcess failed");
            }
        }
    
        private void activateTenantIfNeeded(UUID userId) {
            try {
                UserResponse user = userGrpcClient.getUserById(userId.toString());
    
                if (user == null) {
                    log.warn("activateTenant: user not found userId={}", userId);
                    return;
                }
    
                if (user.getIsEnabled()) {
                    log.info("activateTenant: user already enabled userId={} → skip", userId);
                    return;
                }
    
                String tempPassword = keycloakAdminService.activateUser(user.getKeycloakId());
    
                UserActivatedEvent event = UserActivatedEvent.builder()
                        .userId(userId)
                        .email(user.getEmail())
                        .name(user.getName())
                        .tempPassword(tempPassword)
                        .build();
    
                kafkaTemplate.send("user-activated-topic", event);
                log.info("activateTenant: sent activation event userId={} email={}", userId, user.getEmail());
    
            } catch (Exception e) {
                log.error("activateTenant failed userId={} — contract still completed", userId, e);
            }
        }
    
        @Override
        public ProcessResponse signProcessForAdmin(VnptProcessDto process) {
            try {
                VnptProcessDto updatedProcess = process.withToken(vnptEContractClient.getToken());
                var processResponse = vnptEContractClient.signProcess(updatedProcess);
    
                if (processResponse == null) {
                    throw new IllegalStateException("VNPT signProcess returned null");
                }
    
                if (processResponse.getData() == null) {
                    log.error("VNPT signProcess failed payload={}", processResponse);
                    throw new IllegalStateException("VNPT signProcess failed: " + processResponse.getMessage());
                }
    
                if (processResponse.getSuccess() && processResponse.getData().id() != null) {
                    log.info("VNPT signProcess success: data={}", processResponse.getData());
                    var eContract = eContractRepository.findByDocumentId(String.valueOf(processResponse.getData().id()))
                            .orElseThrow(() -> new NotFoundException("EContract not found for documentId: " + processResponse.getData().id()));
                    eContract.setStatus(EContractStatus.IN_PROGRESS);
                    eContractRepository.save(eContract);
                }
    
                return processResponse.getData();
            } catch (Exception ex) {
                log.error("signProcessForAdmin failed", ex);
                throw ex;
            }
        }
    
        @Override
        @Cacheable(cacheNames = "vnptDocumentById", key = "#documentId")
        public VnptDocumentDto getVnptEContractByDocumentId(String documentId) {
            String token = vnptEContractClient.getToken();
            var eContract = vnptEContractClient.getEContractById(documentId, token);
    
            if (eContract.getData().id() == null) {
                log.error("VNPT get eContract VNPT failed payload={}", eContract);
                throw new NotFoundException("EContract not found for documentId: " + documentId);
            }
    
            return eContract.getData();
        }
    
        private void updateProcess(String token, String documentId, String userCodeFirst, String userCodeSecond, String positionA, String positionB, int pageSign) {
    
            List<ProcessesRequestDTO> processes = List.of(
                    new ProcessesRequestDTO(
                            1,
                            userCodeFirst,
                            "E",
                            positionA,
                            pageSign
                    ),
                    new ProcessesRequestDTO(
                            2,
                            userCodeSecond,
                            "E",
                            positionB,
                            pageSign
                    )
            );
            var request = new VnptUpdateProcessDTO(
                    documentId,
                    true,
                    processes
            );
    
            var result = econtractClient.UpdateProcess(token, request);
            log.info("Update process result: {}", result);
        }
    
        private EContractDto createDocument(UUID actorId, UUID primaryTenantUserId, CreateEContractRequest req, HouseResponse house) {
    
            String templateCode = "LEASE_HOUSE";
            EContractTemplate template = templateRepository.findByCode(templateCode)
                    .orElseThrow(() -> new IllegalStateException("Template with code " + templateCode + " not found"));
    
            Map<String, Object> data = new HashMap<>();
            data.put("LANDLORD_NAME", "Trần Đức Hiệu");
            data.put("LANDLORD_ID", "1234567890");
            data.put("LANDLORD_ID_ISSUE", "30/02/2025 tại Cục Cảnh sát quản lý hành chính về trật tự xã hội");
            data.put("LANDLORD_ADDRESS", "Đức Phú, Mộ Đức, Quảng Ngãi");
            data.put("LANDLORD_PHONE", "0326336224");
            data.put("LANDLORD_EMAIL", "hoangtuzami@gmail.com");
            data.put("LANDLORD_BANK", "03263362224 (TPBank)");
    
            data.put("TENANT_NAME", req.name());
            data.put("TENANT_ID", req.identityNumber());
            data.put("TENANT_ID_ISSUE", dayMonthYear.format(req.dateOfIssue()) + " " + req.placeOfIssue());
            data.put("TENANT_ADDRESS", req.tenantAddress());
            data.put("TENANT_PHONE", req.phoneNumber());
            data.put("TENANT_EMAIL", req.email());
    
            data.put("PROPERTY_ADDRESS", house.getAddress());
    
            // need to change when run production
            data.put("AREA", "123");
            data.put("STRUCTURE", "????");
            data.put("PURPOSE", "Thuê để ở");
            data.put("OWNERSHIP_DOCS", "Chưa có");
    
            data.put("START_DATE", dayMonthYear.format(req.startDate()));
            data.put("END_DATE", dayMonthYear.format(req.endDate()));
            // need to change when run production
            data.put("RENEW_NOTICE_DAYS", "7");
            data.put("RENT_AMOUNT", req.rentAmount());
            // need to change when run production
            data.put("RENT_TEXT", "Tạm chưa có");
            data.put("TAX_FEE_NOTE", "Miễn thuế");
            // need to change when run production
            data.put("PAY_CYCLE", "????");
            data.put("PAY_DAY", req.payDate());
            // need to change when run production
            data.put("LATE_DAYS", "3");
            data.put("LATE_PENALTY", "5");
    
            data.put("DEPOSIT_AMOUNT", req.depositAmount());
            data.put("DEPOSIT_DATE", dayMonthYear.format(req.depositDate()));
            // need to change when run production
            data.put("DEPOSIT_REFUND_DAYS", "3");
    
            data.put("HANDOVER_DATE", dayMonthYear.format(req.handoverDate()));
            data.put("ASSETS_TABLE", createTableInDocumentVN(UUID.fromString(house.getId())));
    
            // need to change when run production
            data.put("UTILITY_RULES", "tạm chưa có");
            data.put("LANDLORD_NOTICE_DAYS", "7");
    
            // need to change when run production
            data.put("CURE_DAYS", "7");
            data.put("MAX_LATE_DAYS", "3");
            data.put("EARLY_TERMINATION_PENALTY", "Mất toàn bộ tiền đã cọc");
            data.put("LANDLORD_BREACH_COMPENSATION", "Đền cọc gấp hai lần");
            data.put("FORCE_MAJEURE_NOTICE_HOURS", "24");
    
            // need to change when run production
            data.put("DISPUTE_DAYS", "7");
            data.put("DISPUTE_FORUM", "Pháp Luật");
    
            data.put("COPIES", "2");
            data.put("EACH_KEEP", "1");
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
                    .createdBy(actorId)
                    .build();
    
            eContractRepository.save(econtract);
    
            return eContractMapper.contractToDto(econtract);
        }
    
    
        private String placeholderEngine(String template, Map<String, Object> data) {
            final Pattern P = Pattern.compile("\\{\\{\\s*([A-Z0-9_]+)\\s*}}");
    
            Matcher m = P.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String key = m.group(1);
                Object value = data.get(key);
                if (value == null) {
                    throw new IllegalStateException("Missing placeholder: " + key);
                }
                String valStr = String.valueOf(value);
                m.appendReplacement(sb, Matcher.quoteReplacement(valStr));
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
            List<AssetItemDto> items = assetGrpcClient.getAssetItemsByHouseId(houseId);
            System.out.println("items " + items);
            for (AssetItemDto item : items) {
                String name = HtmlUtils.htmlEscape(item.getDisplayName());
                Integer quantity = 1; // temporarily set a hard value
    
                sb.append("<tr>")
                        .append("<td style=\"border: 1px solid #000; padding: 6px; text-align: right;\">").append(i).append("</td>")
                        .append("<td style=\"border: 1px solid #000; padding: 6px;\">").append(name).append("</td>")
                        .append("<td style=\"border: 1px solid #000; padding: 6px; text-align: right;\">").append(quantity).append("</td>")
                        .append("</tr>");
                i++;
            }
    
            sb.append("""
                    </tbody>
                    </table>
                    """);
    
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
    
            if (doc.head().selectFirst("meta[charset]") == null) {
                doc.head().prependElement("meta").attr("charset", "UTF-8");
            }
    
            if (doc.head().selectFirst("base[href]") == null) {
                doc.head().prependElement("base").attr("href", baseUri);
            }
    
            doc.outputSettings()
                    .charset("UTF-8")
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml);
    
            return doc.html();
        }
    
        private InputStream cp(String path) {
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
            if (in == null) throw new IllegalStateException("Missing classpath resource: " + path);
            return in;
        }
    
        public VnptPosition getVnptEContractPosition(
                byte[] pdfBytes,
                AnchorBoxVnpt anchor,
                double width,
                double height,
                double offsetY,
                double margin,
                double xAdjust,
                double yAdjust,
                double extraSafeSpace,
                double topPadding
        ) {
            Objects.requireNonNull(pdfBytes, "pdfBytes must not be null");
            Objects.requireNonNull(anchor, "anchor must not be null");
    
            if (anchor.page() <= 0) throw new IllegalArgumentException("anchor.page must be 1-based and > 0");
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("width/height must be > 0");
            if (margin < 0) throw new IllegalArgumentException("margin must be >= 0");
    
            try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
                int lastPage = doc.getNumberOfPages();
                if (anchor.page() > lastPage) {
                    throw new IllegalArgumentException("anchor.page out of range: " + anchor.page() + ", lastPage=" + lastPage);
                }
    
                PDPage page = doc.getPage(anchor.page() - 1);
                PDRectangle box = (page.getCropBox() != null) ? page.getCropBox() : page.getMediaBox();
                double pw = box.getWidth();
                double ph = box.getHeight();
    
                double candidateLlx = clamp(anchor.left() + xAdjust, margin, pw - margin - width);
                double candidateLly = anchor.bottom() - offsetY - height + yAdjust;
    
                double availableBelow = anchor.bottom() - margin;
                double required = offsetY + height + extraSafeSpace;
    
                boolean enoughSamePage = availableBelow >= required;
    
                if (enoughSamePage) {
                    double lly = clamp(candidateLly, margin, ph - margin - height);
                    return new VnptPosition(buildPos(candidateLlx, lly, width, height), anchor.page());
                }
    
                if (anchor.page() < lastPage) {
                    PDPage nextPage = doc.getPage(anchor.page());
                    PDRectangle nb = (nextPage.getCropBox() != null) ? nextPage.getCropBox() : nextPage.getMediaBox();
                    double npw = nb.getWidth();
                    double nph = nb.getHeight();
    
                    double llx = clamp(anchor.left() + xAdjust, margin, npw - margin - width);
    
                    double lly = Math.max(nph - margin - height - topPadding + yAdjust, margin);
                    lly = clamp(lly, margin, nph - margin - height);
    
                    return new VnptPosition(buildPos(llx, lly, width, height), anchor.page() + 1);
                }
    
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
    
        private Map<String, AnchorBoxVnpt> findAnchors(byte[] pdfBytes, List<String> anchorTexts) {
            Objects.requireNonNull(pdfBytes, "pdfBytes");
            Objects.requireNonNull(anchorTexts, "anchorTexts");
    
            if (anchorTexts.isEmpty()) {
                return Collections.emptyMap();
            }
    
            Set<String> remain = new LinkedHashSet<>(anchorTexts);
            Map<String, AnchorBoxVnpt> found = new HashMap<>();
    
            int maxAnchorLen = anchorTexts.stream().mapToInt(String::length).max().orElse(32);
            int keepTail = Math.max(256, maxAnchorLen * 8);
    
            try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
                int totalPages = doc.getNumberOfPages();
    
                for (int pageIdx = 0; pageIdx < totalPages && !remain.isEmpty(); pageIdx++) {
                    PDPage page = doc.getPage(pageIdx);
                    PDRectangle box = (page.getCropBox() != null) ? page.getCropBox() : page.getMediaBox();
                    float pageHeight = box.getHeight();
    
                    AnchorStripper stripper = new AnchorStripper(remain, found, pageHeight, pageIdx + 1, keepTail);
                    stripper.setSortByPosition(true);
                    stripper.setSuppressDuplicateOverlappingText(false);
                    stripper.setStartPage(pageIdx + 1);
                    stripper.setEndPage(pageIdx + 1);
    
                    stripper.getText(doc);
    
                    remain.removeAll(found.keySet());
                }
    
                if (!remain.isEmpty()) {
                    throw new IllegalStateException("Anchors not found in PDF: " + remain);
                }
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
    
            AnchorStripper(Set<String> remain,
                           Map<String, AnchorBoxVnpt> found,
                           float pageHeight,
                           int pageNo1Based,
                           int keepTail) throws IOException {
                super();
                this.remain = remain;
                this.found = found;
                this.pageHeight = pageHeight;
                this.pageNo1Based = pageNo1Based;
                this.keepTail = keepTail;
            }
    
            @Override
            protected void writeString(String text, List<TextPosition> positions) throws IOException {
                if (remain.isEmpty() || positions == null || positions.isEmpty()) {
                    return;
                }
    
                String chunk = buildChunkFromPositions(positions);
                if (chunk.isEmpty()) return;
    
                bufText.append(chunk);
                bufPos.addAll(positions);
    
                scanBuffer();
    
                if (bufText.length() > keepTail) {
                    int cut = bufText.length() - keepTail;
                    bufText.delete(0, cut);
                    if (cut < bufPos.size()) {
                        bufPos.subList(0, cut).clear();
                    } else {
                        bufPos.clear();
                    }
                }
            }
    
            private void scanBuffer() {
                String s = bufText.toString();
    
                for (String a : new ArrayList<>(remain)) {
                    int idx = s.indexOf(a);
                    if (idx < 0) continue;
    
                    int end = idx + a.length();
                    if (end > bufPos.size()) continue;
    
                    AnchorBoxVnpt box = calcAnchorBoxPdfCoords(bufPos.subList(idx, end));
                    found.put(a, box);
                    remain.remove(a);
                }
            }
    
            private String buildChunkFromPositions(List<TextPosition> positions) {
                StringBuilder sb = new StringBuilder(positions.size());
                for (TextPosition tp : positions) {
                    String u = tp.getUnicode();
                    if (u == null) continue;
    
                    sb.append(u);
                }
                return sb.toString();
            }
    
            private AnchorBoxVnpt calcAnchorBoxPdfCoords(List<TextPosition> sub) {
                float xMin = Float.MAX_VALUE;
                float yMaxUL = 0f;
    
                for (TextPosition tp : sub) {
                    float x = tp.getXDirAdj();
                    float w = tp.getWidthDirAdj();
                    xMin = Math.min(xMin, x);
    
                    float y = tp.getYDirAdj();
                    float h = tp.getHeightDir();
                    yMaxUL = Math.max(yMaxUL, y + h);
                }
    
                double pdfLeft = xMin;
                double pdfBottom = pageHeight - yMaxUL;
    
                return new AnchorBoxVnpt(pageNo1Based, pdfLeft, pdfBottom);
            }
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
                        if (at != null && at.isTextual()) {
                            accessToken = at.asText();
                        }
                    }
                }
    
                JsonNode document = dataEl.get("document");
                if (document == null || document.isNull() || !document.isObject()) {
                    throw new IllegalStateException("Missing document: " + body);
                }
    
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
                    if (processed != null && processed.canConvertToInt()) processedByUserId = processed.asInt();
    
                    JsonNode pos = waiting.get("position");
                    if (pos != null && pos.isTextual()) position = pos.asText();
    
                    JsonNode ps = waiting.get("pageSign");
                    if (ps != null && ps.canConvertToInt()) pageSign = ps.asInt();
    
                    JsonNode accessPermission = waiting.get("accessPermission");
                    if (accessPermission != null && accessPermission.isObject()) {
                        JsonNode value = accessPermission.get("value");
                        if (value != null && value.canConvertToInt()) {
                            isOtp = (value.asInt() == 7);
                        }
                    }
                }
    
                return new ProcessLoginInfoDto(
                        waitingProcessId,
                        documentId,
                        documentNo,
                        processedByUserId,
                        accessToken,
                        position,
                        pageSign,
                        isOtp
                );
    
            } catch (Exception e) {
                throw new IllegalStateException("Cannot parse response: " + e.getMessage() + "\nRAW=" + body, e);
            }
    
    
        }
    }
