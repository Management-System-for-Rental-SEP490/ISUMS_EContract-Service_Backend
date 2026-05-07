package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CashDepositReceiptRequest;
import com.isums.contractservice.domains.dtos.CashDepositReceiptResponse;
import com.isums.contractservice.domains.entities.CashDepositReceipt;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.LandlordProfile;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.ContractCashDepositConfirmedEvent;
import com.isums.contractservice.infrastructures.abstracts.CashDepositReceiptService;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.CashDepositReceiptRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.LandlordProfileRepository;
import com.isums.contractservice.utils.NumberToTextConverter;
import com.isums.userservice.grpc.UserResponse;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CashDepositReceiptServiceImpl implements CashDepositReceiptService {

    private final EContractRepository contractRepo;
    private final CashDepositReceiptRepository receiptRepo;
    private final LandlordProfileRepository landlordProfileRepo;
    private final ContractAccessPolicy accessPolicy;
    private final OutboxPublisher outboxPublisher;
    private final UserGrpcClient userGrpc;

    private static final String CASH_DEPOSIT_TOPIC = "contract.cash-deposit-confirmed";
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy").withZone(VN);
    private static final NumberFormat VND_FMT =
            NumberFormat.getNumberInstance(Locale.of("vi", "VN"));

    @Override
    @Transactional
    public CashDepositReceiptResponse confirmCashDeposit(
            UUID contractId,
            UUID actorId,
            String idempotencyKey,
            CashDepositReceiptRequest request) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<CashDepositReceipt> existing = receiptRepo.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("[CashDeposit] Idempotent replay key={} receipt={}",
                        idempotencyKey, existing.get().getReceiptNumber());
                return toResponse(existing.get());
            }
        }

        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new EntityNotFoundException("Contract not found: " + contractId));

        accessPolicy.requireWriteAccess(contract);

        if (contract.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Cash deposit can only be confirmed when contract is COMPLETED, current=" + contract.getStatus());
        }

        DepositStatus current = contract.getDepositStatus();
        if (current == DepositStatus.PAID) {
            throw new IllegalStateException("Deposit already PAID for contract " + contractId);
        }
        if (current != null && current != DepositStatus.UNPAID && current != DepositStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot confirm cash deposit when status=" + current);
        }

        Long expectedAmount = contract.getDepositAmount();
        if (expectedAmount == null || expectedAmount <= 0) {
            throw new IllegalStateException("Contract has no deposit amount configured");
        }
        if (!Objects.equals(request.amount(), expectedAmount)) {
            throw new IllegalArgumentException(
                    "Amount mismatch: contract requires " + expectedAmount + " but received " + request.amount());
        }

        long seq = receiptRepo.nextReceiptSequence();
        int year = ZonedDateTime.now(VN).getYear();
        String receiptNumber = String.format("PT-%d-%06d", year, seq);

        Instant paidAt = request.paidAt() != null ? request.paidAt() : Instant.now();
        String payerName = contract.getTenantName();
        String payeeName = resolvePayeeName(actorId);

        CashDepositReceipt receipt = CashDepositReceipt.builder()
                .contractId(contractId)
                .receiptNumber(receiptNumber)
                .amount(request.amount())
                .paidAt(paidAt)
                .confirmedByUserId(actorId)
                .payerName(payerName)
                .payeeName(payeeName)
                .note(request.note())
                .idempotencyKey(idempotencyKey)
                .build();

        try {
            receipt = receiptRepo.saveAndFlush(receipt);
        } catch (DataIntegrityViolationException ex) {
            CashDepositReceipt active = receiptRepo.findActiveByContractId(contractId).orElse(null);
            if (active != null) {
                throw new IllegalStateException(
                        "Deposit already has an active receipt: " + active.getReceiptNumber());
            }
            throw ex;
        }

        contract.setDepositStatus(DepositStatus.PAID);
        contractRepo.save(contract);

        ContractCashDepositConfirmedEvent event = ContractCashDepositConfirmedEvent.builder()
                .contractId(contractId)
                .tenantId(contract.getUserId())
                .houseId(contract.getHouseId())
                .amount(request.amount())
                .receiptNumber(receiptNumber)
                .paidAt(paidAt)
                .confirmedByUserId(actorId)
                .note(request.note())
                .messageId(UUID.randomUUID().toString())
                .build();

        outboxPublisher.enqueue(CASH_DEPOSIT_TOPIC, contractId.toString(), event, event.messageId());

        log.info("[CashDeposit] Confirmed contractId={} receipt={} amount={} confirmedBy={}",
                contractId, receiptNumber, request.amount(), actorId);

        return toResponse(receipt);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderReceiptPdf(UUID contractId, String receiptNumber) {
        CashDepositReceipt receipt = receiptRepo.findByReceiptNumber(receiptNumber)
                .orElseThrow(() -> new EntityNotFoundException("Receipt not found: " + receiptNumber));

        if (!receipt.getContractId().equals(contractId)) {
            throw new AccessDeniedException("Receipt does not belong to this contract");
        }

        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new EntityNotFoundException("Contract not found: " + contractId));
        accessPolicy.requireReadAccess(contract);

        LandlordProfile landlord = landlordProfileRepo.findByUserId(contract.getCreatedBy())
                .orElse(null);

        String html = buildReceiptHtml(receipt, contract, landlord);
        return renderHtmlToPdf(html);
    }

    @Override
    @Transactional(readOnly = true)
    public CashDepositReceiptResponse getActive(UUID contractId) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new EntityNotFoundException("Contract not found: " + contractId));
        accessPolicy.requireReadAccess(contract);
        return receiptRepo.findActiveByContractId(contractId)
                .map(this::toResponse)
                .orElse(null);
    }

    private String resolvePayeeName(UUID actorId) {
        try {
            UserResponse u = userGrpc.getUserById(actorId.toString());
            return u.getName();
        } catch (Exception e) {
            log.warn("[CashDeposit] Failed to resolve payee actorId={}: {}", actorId, e.getMessage());
            return null;
        }
    }

    private CashDepositReceiptResponse toResponse(CashDepositReceipt r) {
        return CashDepositReceiptResponse.builder()
                .id(r.getId())
                .contractId(r.getContractId())
                .receiptNumber(r.getReceiptNumber())
                .amount(r.getAmount())
                .paidAt(r.getPaidAt())
                .confirmedByUserId(r.getConfirmedByUserId())
                .payerName(r.getPayerName())
                .payeeName(r.getPayeeName())
                .note(r.getNote())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private String buildReceiptHtml(CashDepositReceipt receipt, EContract contract, LandlordProfile landlord) {
        String landlordName = landlord != null ? safe(landlord.getFullName()) : "";
        String landlordAddress = landlord != null ? safe(landlord.getAddress()) : "";
        String landlordTaxCode = landlord != null ? safe(landlord.getTaxCode()) : "";
        String contractNo = contract.getDocumentNo() != null
                ? safe(contract.getDocumentNo())
                : contract.getId().toString().substring(0, 8).toUpperCase();
        String amountText = capitalize(NumberToTextConverter.convertVi(receipt.getAmount()));
        String reasonText = "Đặt cọc hợp đồng thuê nhà " + contractNo;
        String paidAtText = DT_FORMATTER.format(receipt.getPaidAt());
        String payerName = safe(receipt.getPayerName());
        String payeeName = safe(receipt.getPayeeName());
        String note = safe(receipt.getNote());

        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset=\"UTF-8\"/><style>")
            .append("@page{size:A5;margin:14mm 16mm}")
            .append("body{font-family:'Times New Roman',serif;font-size:12pt;color:#000}")
            .append(".hdr{text-align:center;margin-bottom:6mm}")
            .append(".hdr .org{font-weight:bold;text-transform:uppercase;font-size:13pt}")
            .append(".hdr .meta{font-size:10pt}")
            .append(".title{text-align:center;font-weight:bold;font-size:18pt;margin:6mm 0 2mm}")
            .append(".sub{text-align:center;font-size:10pt;margin-bottom:6mm}")
            .append(".row{margin:2mm 0}")
            .append(".lbl{display:inline-block;min-width:42mm}")
            .append(".val{font-weight:bold}")
            .append(".amount{font-size:14pt;font-weight:bold}")
            .append(".sign{display:flex;justify-content:space-between;margin-top:14mm}")
            .append(".sign .col{width:45%;text-align:center}")
            .append(".sign .role{font-weight:bold;font-style:italic}")
            .append(".sign .blank{height:22mm}")
            .append(".note{margin-top:6mm;font-style:italic;font-size:10pt}")
            .append("</style></head><body>")
            .append("<div class=\"hdr\"><div class=\"org\">").append(landlordName).append("</div>")
            .append("<div class=\"meta\">").append(landlordAddress).append("</div>");
        if (!landlordTaxCode.isEmpty()) {
            html.append("<div class=\"meta\">MST: ").append(landlordTaxCode).append("</div>");
        }
        html.append("</div>")
            .append("<div class=\"title\">PHIẾU THU TIỀN MẶT</div>")
            .append("<div class=\"sub\">Số: ").append(safe(receipt.getReceiptNumber())).append("</div>")
            .append("<div class=\"row\"><span class=\"lbl\">Họ tên người nộp:</span><span class=\"val\">").append(payerName).append("</span></div>")
            .append("<div class=\"row\"><span class=\"lbl\">Lý do nộp:</span><span class=\"val\">").append(reasonText).append("</span></div>")
            .append("<div class=\"row\"><span class=\"lbl\">Số tiền:</span><span class=\"amount\">")
            .append(VND_FMT.format(receipt.getAmount())).append(" ₫</span></div>")
            .append("<div class=\"row\"><span class=\"lbl\">Bằng chữ:</span><span class=\"val\">").append(amountText).append(" đồng</span></div>")
            .append("<div class=\"row\"><span class=\"lbl\">Thời điểm:</span><span class=\"val\">").append(paidAtText).append("</span></div>");
        if (!note.isEmpty()) {
            html.append("<div class=\"note\">Ghi chú: ").append(note).append("</div>");
        }
        html.append("<div class=\"sign\">")
            .append("<div class=\"col\"><div class=\"role\">Người nộp tiền</div><div class=\"meta\">(Ký, ghi rõ họ tên)</div><div class=\"blank\"></div><div>").append(payerName).append("</div></div>")
            .append("<div class=\"col\"><div class=\"role\">Người thu tiền</div><div class=\"meta\">(Ký, ghi rõ họ tên)</div><div class=\"blank\"></div><div>").append(payeeName).append("</div></div>")
            .append("</div>")
            .append("</body></html>");
        return html.toString();
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
        if (doc.head().selectFirst("meta[charset]") == null) {
            doc.head().prependElement("meta").attr("charset", "UTF-8");
        }
        if (doc.head().selectFirst("base[href]") == null) {
            doc.head().prependElement("base").attr("href", baseUri);
        }
        doc.outputSettings().charset("UTF-8")
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml);
        return doc.html();
    }

    private InputStream cp(String path) {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource: " + path);
        }
        return in;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
