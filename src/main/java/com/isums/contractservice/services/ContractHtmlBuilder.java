package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CoTenantDto;

import com.isums.contractservice.domains.dtos.CreateEContractRequest;

import com.isums.contractservice.domains.dtos.ReplacementContext;

import com.isums.contractservice.domains.enums.RelocationRequestKind;

import com.isums.contractservice.domains.entities.EContractTemplate;

import com.isums.contractservice.domains.entities.LandlordProfile;

import com.isums.contractservice.domains.enums.ContractLanguage;

import com.isums.contractservice.domains.enums.TenantType;

import com.isums.contractservice.infrastructures.repositories.EContractTemplateRepository;

import com.isums.contractservice.utils.ContractI18n;

import com.isums.contractservice.utils.NumberToTextConverter;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.Instant;

import java.time.ZoneOffset;

import java.time.format.DateTimeFormatter;

import java.util.HashMap;

import java.util.List;

import java.util.Map;

import java.util.UUID;

import java.util.regex.Matcher;

import java.util.regex.Pattern;

@Slf4j

@Service

@RequiredArgsConstructor

public class ContractHtmlBuilder {

    public static final String CODE_VI = "LEASE_HOUSE_VI";

    public static final String CODE_BILINGUAL = "LEASE_HOUSE_BILINGUAL";

    public static final String CODE_LEGACY = "LEASE_HOUSE";

    private static final DateTimeFormatter DMY =

            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter DMY_JA =

            DateTimeFormatter.ofPattern("yyyy年MM月dd日").withZone(ZoneOffset.UTC);

    private static final String ISSUE_CITY_VI = "TP.HCM";

    private static final String ISSUE_CITY_JA = "ホーチミン市";

    private static final String ISSUE_CITY_EN = "Ho Chi Minh City";

    private static String formatMoney(java.math.BigDecimal amount) {

        if (amount == null) return "";

        return java.text.NumberFormat.getNumberInstance(java.util.Locale.US)

                .format(amount);

    }

    private static String formatMoney(Long amount) {

        if (amount == null) return "";

        return java.text.NumberFormat.getNumberInstance(java.util.Locale.US)

                .format(amount);

    }

    private static String composeTaxCodeRow(String taxCode, String label) {
        if (taxCode == null || taxCode.isBlank() || "—".equals(taxCode.trim())) return "";
        return "<tr><td>" + label + "</td><td>" + taxCode.trim() + "</td></tr>";
    }

    private static String composeBankLine(String account, String bankName) {

        String a = account == null ? "" : account.trim();

        String b = bankName == null ? "" : bankName.trim();

        if (a.isEmpty() && b.isEmpty()) return "—";

        if (b.isEmpty()) return a;

        if (a.isEmpty()) return b;

        return a + " — " + b;

    }

    private static String formatDate(java.time.LocalDate date, String lang) {

        if (date == null) return "";

        return "ja".equals(lang) ? DMY_JA.format(date.atStartOfDay(ZoneOffset.UTC).toInstant())

                                 : DMY.format(date.atStartOfDay(ZoneOffset.UTC).toInstant());

    }

    private static String formatDate(java.time.Instant inst, String lang) {

        if (inst == null) return "";

        return "ja".equals(lang) ? DMY_JA.format(inst) : DMY.format(inst);

    }

    private static String formatDateIsoLike(String iso, String lang) {

        if (iso == null || iso.isBlank()) return "";

        try {

            java.time.LocalDate d = java.time.LocalDate.parse(iso.substring(0, 10));

            return formatDate(d, lang);

        } catch (Exception e) {

            return iso;

        }

    }

    private static String issueCity(String lang) {

        return switch (lang == null ? "vi" : lang) {

            case "ja" -> ISSUE_CITY_JA;

            case "en" -> ISSUE_CITY_EN;

            default   -> ISSUE_CITY_VI;

        };

    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Z0-9_][A-Za-z0-9_.]*)\\s*}}");

    private final EContractTemplateRepository templateRepo;

    private final ContractTranslationService translationService;

    public String build(BuildInput in) {

        ContractLanguage lang = in.language() != null ? in.language() : ContractLanguage.VI;

        if (lang == ContractLanguage.VI) {

            return renderVi(in);

        }

        return renderBilingual(in, lang);

    }

    private String renderVi(BuildInput in) {

        EContractTemplate tmpl = templateRepo.findByCode(CODE_VI)

                .or(() -> templateRepo.findByCode(CODE_LEGACY))

                .orElseThrow(() -> new IllegalStateException(

                        "Template not found: " + CODE_VI + " or " + CODE_LEGACY));

        Map<String, Object> data = new HashMap<>(viDataMap(in));

        return render(tmpl.getContentHtml(), data);

    }

    private Map<String, Object> viDataMap(BuildInput in) {

        CreateEContractRequest req = in.request();

        LandlordProfile lp = in.landlord();

        String propertyAddress = in.propertyAddress();

        Map<String, Object> data = new HashMap<>();

        data.put("LANDLORD_NAME", nz(lp.getFullName()));

        data.put("LANDLORD_ID", nz(lp.getIdentityNumber()));

        data.put("LANDLORD_DOB", formatDate(lp.getDateOfBirth(), "vi"));

        data.put("LANDLORD_ID_ISSUE",

                (lp.getIdentityIssueDate() != null && !lp.getIdentityIssueDate().isBlank()

                        ? formatDateIsoLike(lp.getIdentityIssueDate(), "vi") + " " : "")

                        + nz(lp.getIdentityIssuePlace()));

        data.put("LANDLORD_ADDRESS", nz(lp.getAddress()));

        data.put("LANDLORD_PERMANENT_ADDRESS", nz(lp.getPermanentAddress()));

        data.put("LANDLORD_PHONE", nz(lp.getPhoneNumber()));

        data.put("LANDLORD_EMAIL", nz(lp.getEmail()));

        data.put("LANDLORD_TAX_CODE", blankDash(lp.getTaxCode()));
        data.put("LANDLORD_TAX_CODE_ROW", composeTaxCodeRow(lp.getTaxCode(), "MST:"));

        data.put("LANDLORD_BANK", nz(lp.getBankAccount()));

        data.put("LANDLORD_BANK_NAME", nz(lp.getBankName()));

        data.put("LANDLORD_BANK_LINE", composeBankLine(lp.getBankAccount(), lp.getBankName()));

        data.put("TENANT_NAME", nz(req.name()));

        boolean isForeigner = req.tenantTypeOrDefault() == TenantType.FOREIGNER;

        data.put("TENANT_ID", nz(isForeigner

                ? (req.passportNumber() != null && !req.passportNumber().isBlank()

                        ? req.passportNumber() : req.identityNumber())

                : (req.identityNumber() != null && !req.identityNumber().isBlank()

                        ? req.identityNumber() : req.passportNumber())));

        data.put("TENANT_ID_ISSUE", formatTenantIdIssue(req, "vi"));

        data.put("TENANT_DOB", formatDate(req.dateOfBirth(), "vi"));

        data.put("TENANT_GENDER", ContractI18n.gender(req.gender(), "vi"));

        data.put("TENANT_IDENTITY_KIND",

                ContractI18n.identityKind(req.tenantTypeOrDefault(), "vi"));

        data.put("TENANT_NATIONALITY", defaultNationality(req));

        data.put("TENANT_OCCUPATION", nz(req.occupation()));

        data.put("TENANT_PERMANENT_ADDRESS", nz(req.permanentAddress()));

        data.put("TENANT_ADDRESS", nz(req.tenantAddress()));

        data.put("TENANT_PHONE", nz(req.phoneNumber()));

        data.put("TENANT_EMAIL", nz(req.email()));

        data.put("TENANT_VISA", formatTenantVisa(req, "vi"));

        data.put("PROPERTY_ADDRESS", nz(propertyAddress));

        data.put("USABLE_AREA_M2", nz(in.propertyAreaM2()));

        data.put("STRUCTURE", structureLabel(in.propertyStructure(), "vi"));

        data.put("LAND_CERT_NUMBER", nz(in.houseLandCertNumber()));

        data.put("LAND_CERT_ISSUE_DATE", nz(in.houseLandCertIssueDate()));

        data.put("LAND_CERT_ISSUER", nz(in.houseLandCertIssuer()));

        data.put("START_DATE", DMY.format(req.startDate()));

        data.put("END_DATE", DMY.format(req.endDate()));

        data.put("RENEW_NOTICE_DAYS", req.renewNoticeDaysOrDefault());

        data.put("HANDOVER_DATE", DMY.format(req.effectiveHandoverDate()));

        data.put("DEPOSIT_DATE", DMY.format(req.effectiveDepositDate()));

        data.put("EFFECTIVE_DATE", issueCity("vi") + ", ngày " + DMY.format(Instant.now()));

        data.put("DOCUMENT_NO", in.documentNoOverride() != null && !in.documentNoOverride().isBlank()
                ? in.documentNoOverride()
                : "EC_" + System.currentTimeMillis());

        data.put("RENT_AMOUNT", req.rentAmount());

        data.put("RENT_TEXT", NumberToTextConverter.convert(req.rentAmount()));

        data.put("DEPOSIT_AMOUNT", req.depositAmount());

        data.put("DEPOSIT_TEXT", NumberToTextConverter.convert(req.depositAmount()));

        data.put("RENT_AMOUNT_FMT", formatMoney(req.rentAmount()));

        data.put("DEPOSIT_AMOUNT_FMT", formatMoney(req.depositAmount()));

        data.put("TAX_FEE_NOTE", req.taxFeeNoteOrDefault());

        data.put("PAY_CYCLE", req.payCycleOrDefault());

        data.put("PAY_DAY", req.payDate());

        data.put("LATE_DAYS", req.lateDaysOrDefault());

        data.put("LATE_PENALTY", req.latePenaltyPercentOrDefault());

        data.put("DEPOSIT_REFUND_DAYS", req.depositRefundDaysOrDefault());

        data.put("TAX_RESPONSIBILITY",

                ContractI18n.taxResponsibility(req.taxResponsibilityOrDefault(), "vi"));

        Map<String, Object> meters = in.meters();

        data.put("METER_ELECTRIC", meters.getOrDefault("electric", "—"));

        data.put("METER_WATER", meters.getOrDefault("water", "—"));

        data.put("METER_NOTE", meters.getOrDefault("note", ""));

        data.put("PET_POLICY", ContractI18n.petPolicy(req.petPolicyOrDefault(), "vi"));

        data.put("SMOKING_POLICY", ContractI18n.smokingPolicy(req.smokingPolicyOrDefault(), "vi"));

        data.put("SUBLEASE_POLICY", ContractI18n.subleasePolicy(req.subleasePolicyOrDefault(), "vi"));

        data.put("VISITOR_POLICY", ContractI18n.visitorPolicy(req.visitorPolicyOrDefault(), "vi"));

        data.put("TEMP_RESIDENCE_REGISTER_BY",

                ContractI18n.tempResidenceBy(req.tempResidenceRegisterByOrDefault(), "vi"));

        data.put("LANDLORD_NOTICE_DAYS", req.landlordNoticeDaysOrDefault());

        data.put("CURE_DAYS", req.cureDaysOrDefault());

        data.put("MAX_LATE_DAYS", req.maxLateDaysOrDefault());

        data.put("EARLY_TERMINATION_PENALTY", req.earlyTerminationPenaltyOrDefault());

        data.put("LANDLORD_BREACH_COMPENSATION", req.landlordBreachCompensationOrDefault());

        data.put("FORCE_MAJEURE_NOTICE_HOURS", resolveForceMajeureHours(req, in.landlord()));

        data.put("DISPUTE_DAYS", req.disputeDaysOrDefault());

        data.put("DISPUTE_FORUM", req.disputeForumOrDefault());

        data.put("PREVAILING_LANGUAGE_CLAUSE", ContractI18n.boilerplate("PREVAILING_LANGUAGE_CLAUSE", "vi"));

        data.put("SIGN_A_LABEL", ContractI18n.boilerplate("SIGN_A_LABEL", "vi"));

        data.put("SIGN_B_LABEL", ContractI18n.boilerplate("SIGN_B_LABEL", "vi"));

        data.put("SIGN_SUBTITLE", ContractI18n.boilerplate("SIGN_SUBTITLE", "vi"));

        data.put("CO_TENANTS_BLOCK", nz(in.coTenantsBlockHtml()).isEmpty()

                ? renderCoTenantsBlock(req.coTenants(), "vi") : in.coTenantsBlockHtml());

        data.put("ASSETS_TABLE", in.assetsTableHtml());

        data.put("UTILITY_RULES", "Theo thỏa thuận hai bên (thuê nguyên căn — bên B tự đăng ký, thanh toán)");

        data.put("REPLACEMENT_BLOCK", renderReplacementBlockVi(in.replacement()));

        return data;

    }

    private String renderReplacementBlockVi(ReplacementContext rc) {
        if (rc == null) return "";

        String reasonText = switch (rc.requestKind() == null ? RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST : rc.requestKind()) {
            case ACTIVE_LEASE_TENANT_UPGRADE -> "do Bên B đề nghị chuyển sang Nhà thuê khác phù hợp hơn với nhu cầu sử dụng";
            case LANDLORD_FAULT_UNINHABITABLE -> "do Nhà thuê tại Hợp đồng cũ không bảo đảm điều kiện sử dụng do nguyên nhân không thuộc lỗi Bên B";
            default -> "do hai bên thỏa thuận chấm dứt Hợp đồng cũ và ký kết Hợp đồng mới";
        };

        long oldDeposit = rc.oldDepositAmount() == null ? 0L : rc.oldDepositAmount();
        long transferred = rc.transferredDepositAmount() == null ? 0L : rc.transferredDepositAmount();
        long newDeposit = rc.newDepositAmount() == null ? 0L : rc.newDepositAmount();
        long additional = rc.additionalDepositAmount() == null ? 0L : rc.additionalDepositAmount();
        long oldRent = rc.oldRentProratedAmount() == null ? 0L : rc.oldRentProratedAmount();
        long oldUtilities = rc.oldUtilitiesAmount() == null ? 0L : rc.oldUtilitiesAmount();
        long oldDamage = rc.oldDamageAmount() == null ? 0L : rc.oldDamageAmount();
        long adminFee = rc.adminFeeAmount() == null ? 0L : rc.adminFeeAmount();
        long totalAdditional = rc.totalAdditionalPaymentAmount() == null ? 0L : rc.totalAdditionalPaymentAmount();

        long diff = newDeposit - transferred;
        String diffDirection = diff > 0
                ? "Bên B nộp thêm " + formatMoney(diff) + " VNĐ"
                : (diff < 0 ? "Bên A hoàn lại " + formatMoney(-diff) + " VNĐ" : "không phát sinh chênh lệch");

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"clause-title\">Điều 14. Tiếp nối Hợp đồng cũ</div>");
        sb.append("<p>1. Hợp đồng này được giao kết để thay thế Hợp đồng thuê nhà ở số <b>")
                .append(nz(rc.oldContractNumber()))
                .append("</b> ký ngày <b>")
                .append(formatDate(rc.oldContractSignedAt(), "vi"))
                .append("</b> (sau đây gọi là “Hợp đồng cũ”), ").append(reasonText).append(".</p>");
        sb.append("<p>2. Hai bên thống nhất chấm dứt Hợp đồng cũ theo Điều 422 Bộ luật Dân sự 2015 kể từ ngày bàn giao Nhà thuê tại Hợp đồng này (")
                .append(formatDate(rc.newHandoverDate(), "vi"))
                .append(").</p>");
        sb.append("<p>3. Bảng quyết toán chuyển tiếp:</p>");
        sb.append("<table>")
                .append("<tr><th style=\"text-align:left;\">Khoản mục</th><th style=\"text-align:right;\">Số tiền (VNĐ)</th></tr>")
                .append("<tr><td>Tiền đặt cọc Hợp đồng cũ</td><td style=\"text-align:right;\">").append(formatMoney(oldDeposit)).append("</td></tr>")
                .append("<tr><td>Chuyển sang Hợp đồng này (Điều 328 BLDS 2015)</td><td style=\"text-align:right;\">").append(formatMoney(transferred)).append("</td></tr>")
                .append("<tr><td>Cọc Hợp đồng này</td><td style=\"text-align:right;\">").append(formatMoney(newDeposit)).append("</td></tr>")
                .append("<tr><td>Chênh lệch</td><td style=\"text-align:right;\">").append(diffDirection).append("</td></tr>")
                .append("<tr><td>Tiền thuê còn quyết toán Hợp đồng cũ</td><td style=\"text-align:right;\">").append(formatMoney(oldRent)).append("</td></tr>")
                .append("<tr><td>Điện nước/dịch vụ chưa thanh toán</td><td style=\"text-align:right;\">").append(formatMoney(oldUtilities)).append("</td></tr>")
                .append("<tr><td>Sửa chữa thiệt hại do Bên B</td><td style=\"text-align:right;\">").append(formatMoney(oldDamage)).append("</td></tr>")
                .append("<tr><td>Phí xử lý/hành chính</td><td style=\"text-align:right;\">").append(formatMoney(adminFee)).append("</td></tr>")
                .append("<tr><td><b>Tổng Bên B phải thanh toán thêm</b></td><td style=\"text-align:right;\"><b>").append(formatMoney(additional == 0 ? totalAdditional : additional)).append("</b></td></tr>")
                .append("</table>");
        sb.append("<p>4. Bên B cam kết thanh toán toàn bộ chênh lệch và công nợ Hợp đồng cũ trong vòng 7 ngày kể từ ngày ký Hợp đồng này. Quá hạn áp dụng lãi chậm trả theo Điều 3 Hợp đồng này.</p>");

        if (rc.inspectionNote() != null && !rc.inspectionNote().isBlank()) {
            sb.append("<p>5. Ghi nhận hiện trạng Nhà thuê cũ tại thời điểm bàn giao: ")
                    .append(escapeHtml(rc.inspectionNote()))
                    .append("</p>");
        }

        if (rc.legalBasisSnapshot() != null && !rc.legalBasisSnapshot().isBlank()) {
            sb.append("<p class=\"legal-note\">Căn cứ pháp lý đính kèm: ")
                    .append(escapeHtml(rc.legalBasisSnapshot()))
                    .append("</p>");
        }
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String renderBilingual(BuildInput in, ContractLanguage lang) {

        EContractTemplate tmpl = templateRepo.findByCode(CODE_BILINGUAL)

                .orElseThrow(() -> new IllegalStateException(

                        "Template not found: " + CODE_BILINGUAL));

        String xxCode = lang == ContractLanguage.VI_JA ? "ja" : "en";

        Map<String, Object> data = bilingualDataMap(in, xxCode);

        return render(tmpl.getContentHtml(), data);

    }

    private Map<String, Object> bilingualDataMap(BuildInput in, String xxCode) {

        CreateEContractRequest req = in.request();

        Map<String, Object> vi = viDataMap(in);

        Map<String, Object> data = new HashMap<>();

        for (Map.Entry<String, Object> e : vi.entrySet()) {

            if (isUniversalKey(e.getKey())) data.put(e.getKey(), e.getValue());

        }

        for (String k : VI_XX_SPLIT_KEYS) {

            Object v = vi.get(k);

            if (v != null) data.put(k + ".vi", v);

        }

        data.put("RENT_TEXT.xx", NumberToTextConverter.convert(req.rentAmount(), xxCode));

        data.put("DEPOSIT_TEXT.xx", NumberToTextConverter.convert(req.depositAmount(), xxCode));

        data.put("PAY_CYCLE.xx", ContractI18n.payCycle(req.payCycleOrDefault(), xxCode));

        data.put("PAY_CYCLE.vi", ContractI18n.payCycle(req.payCycleOrDefault(), "vi"));

        data.put("TAX_FEE_NOTE.xx", translate(req.taxFeeNoteOrDefault(), xxCode));

        data.put("TAX_RESPONSIBILITY.xx",

                ContractI18n.taxResponsibility(req.taxResponsibilityOrDefault(), xxCode));

        data.put("TENANT_IDENTITY_KIND.xx",

                ContractI18n.identityKind(req.tenantTypeOrDefault(), xxCode));

        data.put("TENANT_GENDER.xx", ContractI18n.gender(req.gender(), xxCode));

        data.put("TENANT_OCCUPATION.xx", translateOrNa(req.occupation(), xxCode));

        data.put("TENANT_PERMANENT_ADDRESS.xx", translateOrNa(req.permanentAddress(), xxCode));

        LandlordProfile lp = in.landlord();

        data.put("LANDLORD_DOB.xx", formatDate(lp.getDateOfBirth(), xxCode));

        data.put("TENANT_DOB.xx",   formatDate(req.dateOfBirth(),    xxCode));

        data.put("START_DATE.vi",    DMY.format(req.startDate()));

        data.put("START_DATE.xx",    formatDate(req.startDate(),    xxCode));

        data.put("END_DATE.vi",      DMY.format(req.endDate()));

        data.put("END_DATE.xx",      formatDate(req.endDate(),      xxCode));

        data.put("DEPOSIT_DATE.vi",  DMY.format(req.effectiveDepositDate()));

        data.put("DEPOSIT_DATE.xx",  formatDate(req.effectiveDepositDate(),  xxCode));

        data.put("HANDOVER_DATE.vi", DMY.format(req.effectiveHandoverDate()));

        data.put("HANDOVER_DATE.xx", formatDate(req.effectiveHandoverDate(), xxCode));

        String bankLine = composeBankLine(lp.getBankAccount(), lp.getBankName());

        data.put("LANDLORD_BANK_LINE.vi", bankLine);

        data.put("LANDLORD_BANK_LINE.xx", bankLine);

        String taxCode = in.landlord().getTaxCode();
        data.put("LANDLORD_TAX_CODE_ROW.vi", composeTaxCodeRow(taxCode, "MST:"));
        data.put("LANDLORD_TAX_CODE_ROW.xx",
                composeTaxCodeRow(taxCode,
                        ContractI18n.boilerplate("LABEL_TAX_CODE", xxCode)));

        data.put("STRUCTURE.xx", translate(structureLabel(in.propertyStructure(), "vi"), xxCode));

        data.put("PET_POLICY.xx", ContractI18n.petPolicy(req.petPolicyOrDefault(), xxCode));

        data.put("SMOKING_POLICY.xx", ContractI18n.smokingPolicy(req.smokingPolicyOrDefault(), xxCode));

        data.put("SUBLEASE_POLICY.xx", ContractI18n.subleasePolicy(req.subleasePolicyOrDefault(), xxCode));

        data.put("VISITOR_POLICY.xx", ContractI18n.visitorPolicy(req.visitorPolicyOrDefault(), xxCode));

        data.put("TEMP_RESIDENCE_REGISTER_BY.xx",

                ContractI18n.tempResidenceBy(req.tempResidenceRegisterByOrDefault(), xxCode));

        data.put("EARLY_TERMINATION_PENALTY.xx", translate(req.earlyTerminationPenaltyOrDefault(), xxCode));

        data.put("LANDLORD_BREACH_COMPENSATION.xx",

                translate(req.landlordBreachCompensationOrDefault(), xxCode));

        data.put("DISPUTE_FORUM.xx", translate(req.disputeForumOrDefault(), xxCode));

        data.put("METER_NOTE.xx", translate((String) in.meters().getOrDefault("note", ""), xxCode));

        data.put("LAND_CERT_ISSUER.xx", translateOrNa(in.houseLandCertIssuer(), xxCode));

        String jaHeading = "ja".equals(xxCode)

                ? issueCity(xxCode) + "、" + DMY_JA.format(Instant.now())

                : issueCity(xxCode) + ", " + DMY.format(Instant.now());

        data.put("EFFECTIVE_DATE.xx", jaHeading);

        data.put("DOCUMENT_NO.xx", vi.get("DOCUMENT_NO"));

        data.put("PREVAILING_LANGUAGE_CLAUSE.xx",

                ContractI18n.boilerplate("PREVAILING_LANGUAGE_CLAUSE", xxCode));

        data.put("SIGN_A_LABEL.vi", ContractI18n.boilerplate("SIGN_A_LABEL", "vi"));

        data.put("SIGN_A_LABEL.xx", ContractI18n.boilerplate("SIGN_A_LABEL", xxCode));

        data.put("SIGN_B_LABEL.vi", ContractI18n.boilerplate("SIGN_B_LABEL", "vi"));

        data.put("SIGN_B_LABEL.xx", ContractI18n.boilerplate("SIGN_B_LABEL", xxCode));

        data.put("SIGN_SUBTITLE.vi", ContractI18n.boilerplate("SIGN_SUBTITLE", "vi"));

        data.put("SIGN_SUBTITLE.xx", ContractI18n.boilerplate("SIGN_SUBTITLE", xxCode));

        for (String key : BOILERPLATE_KEYS) {

            data.put(key + ".xx", ContractI18n.boilerplate(key, xxCode));

            data.put(key + ".vi", ContractI18n.boilerplate(key, "vi"));

        }

        String lpIssueDate = in.landlord().getIdentityIssueDate();

        String lpIdIssueVi = (lpIssueDate != null && !lpIssueDate.isBlank()

                ? formatDateIsoLike(lpIssueDate, "vi") + " " : "")

                + nz(in.landlord().getIdentityIssuePlace());

        data.put("LANDLORD_ID_ISSUE.vi", lpIdIssueVi);

        data.put("LANDLORD_ID_ISSUE.xx",

                (lpIssueDate != null && !lpIssueDate.isBlank()

                        ? formatDateIsoLike(lpIssueDate, xxCode) + " " : "")

                + translateOrNa(in.landlord().getIdentityIssuePlace(), xxCode));

        data.put("TENANT_ID_ISSUE.vi", formatTenantIdIssue(req, "vi"));

        data.put("TENANT_ID_ISSUE.xx", formatTenantIdIssue(req, xxCode));

        data.put("TENANT_NATIONALITY.vi", nz(req.nationality()));

        data.put("TENANT_NATIONALITY.xx", translateNationality(req.nationality(), xxCode));

        data.put("TENANT_VISA.vi", formatTenantVisa(req, "vi"));

        data.put("TENANT_VISA.xx", formatTenantVisa(req, xxCode));

        data.put("ASSETS_TABLE.vi", in.assetsTableHtml());

        data.put("ASSETS_TABLE.xx", in.assetsTableHtmlXx());

        data.put("CO_TENANTS_BLOCK.vi", in.coTenantsBlockHtml());

        data.put("CO_TENANTS_BLOCK.xx", in.coTenantsBlockHtmlXx());

        return data;

    }

    private static boolean isUniversalKey(String k) {

        return switch (k) {

            case "LANDLORD_NAME", "LANDLORD_ID", "LANDLORD_DOB", "LANDLORD_ID_ISSUE",

                 "LANDLORD_PERMANENT_ADDRESS", "LANDLORD_PHONE", "LANDLORD_EMAIL",

                 "LANDLORD_TAX_CODE", "LANDLORD_TAX_CODE_ROW", "LANDLORD_BANK", "LANDLORD_BANK_NAME",

                 "TENANT_NAME", "TENANT_ID", "TENANT_ID_ISSUE", "TENANT_DOB",

                 "TENANT_NATIONALITY", "TENANT_PHONE", "TENANT_EMAIL", "TENANT_VISA",

                 "PROPERTY_ADDRESS", "USABLE_AREA_M2",

                 "LAND_CERT_NUMBER", "LAND_CERT_ISSUE_DATE",

                 "START_DATE", "END_DATE", "HANDOVER_DATE", "DEPOSIT_DATE",

                 "RENT_AMOUNT", "DEPOSIT_AMOUNT", "RENT_AMOUNT_FMT", "DEPOSIT_AMOUNT_FMT", "LANDLORD_BANK_LINE",

                 "PAY_DAY", "LATE_DAYS", "LATE_PENALTY",

                 "DEPOSIT_REFUND_DAYS", "RENEW_NOTICE_DAYS", "LANDLORD_NOTICE_DAYS",

                 "CURE_DAYS", "MAX_LATE_DAYS", "FORCE_MAJEURE_NOTICE_HOURS",

                 "DISPUTE_DAYS",

                 "METER_ELECTRIC", "METER_WATER",

                 "CO_TENANTS_BLOCK", "ASSETS_TABLE" -> true;

            default -> false;

        };

    }

    private static final List<String> VI_XX_SPLIT_KEYS = List.of(

            "STRUCTURE", "PAY_CYCLE", "RENT_TEXT", "DEPOSIT_TEXT",

            "TAX_FEE_NOTE", "TAX_RESPONSIBILITY",

            "TENANT_IDENTITY_KIND", "TENANT_GENDER",

            "TENANT_OCCUPATION", "TENANT_PERMANENT_ADDRESS",

            "LANDLORD_DOB", "TENANT_DOB",

            "PET_POLICY", "SMOKING_POLICY", "SUBLEASE_POLICY", "VISITOR_POLICY",

            "TEMP_RESIDENCE_REGISTER_BY",

            "EARLY_TERMINATION_PENALTY", "LANDLORD_BREACH_COMPENSATION",

            "DISPUTE_FORUM", "METER_NOTE",

            "LAND_CERT_ISSUER", "EFFECTIVE_DATE", "DOCUMENT_NO",

            "PREVAILING_LANGUAGE_CLAUSE"

    );

    private static final List<String> BOILERPLATE_KEYS = List.of(

            "HEADING_COUNTRY", "HEADING_MOTTO", "HEADING_TITLE", "HEADING_NO",

            "LEGAL_BASIS", "LAW_CIVIL", "LAW_HOUSING", "LAW_REALESTATE",

            "LAW_RESIDENCE", "LAW_PARTY_AGREEMENT",

            "LABEL_LANDLORD", "LABEL_TENANT",

            "LABEL_FULL_NAME", "LABEL_DOB", "LABEL_ID", "LABEL_ID_ISSUE",

            "LABEL_PERM_ADDRESS", "LABEL_PHONE_EMAIL", "LABEL_TAX_CODE",

            "LABEL_BANK", "LABEL_IDENTITY_KIND", "LABEL_NATIONALITY",

            "LABEL_OCCUPATION", "LABEL_DOB_GENDER", "LABEL_VISA",

            "LABEL_PET", "LABEL_SMOKING", "LABEL_SUBLEASE", "LABEL_VISITOR",

            "LABEL_TEMP_RES", "LAND_CERT_ISSUED_BY",

            "ART_1_TITLE", "ART_1_P1", "ART_1_P2_AREA", "ART_1_P2_STRUCTURE", "ART_1_P3_CERT",

            "ART_2_TITLE", "ART_2_FROM", "ART_2_TO", "ART_2_DAYS", "ART_2_RENEWAL",

            "ART_3_TITLE", "ART_3_RENT", "ART_3_TAX", "ART_3_TAX_RESP",

            "ART_3_CYCLE", "ART_3_PAY_DAY", "ART_3_DAYS", "ART_3_LATE",

            "ART_4_TITLE", "ART_4_AMOUNT", "ART_4_PAID_ON", "ART_4_REFUND", "ART_4_DAYS",

            "ART_5_TITLE", "ART_5_HANDOVER", "ART_5_METER",

            "ART_6_TITLE", "ART_6_BODY",

            "ART_7_TITLE",

            "ART_8_TITLE", "ART_8_LI1", "ART_8_LI2", "ART_8_LI3", "ART_8_DAYS", "ART_8_LI4",

            "ART_9_TITLE", "ART_9_LI1", "ART_9_LI2", "ART_9_LI3", "ART_9_LI4",

            "ART_10_TITLE", "ART_10_LI1", "ART_10_LI2", "ART_10_LI3", "ART_10_LI4",

            "ART_10_DAYS", "ART_10_A_FAULT", "ART_10_B_FAULT",
            "ART_10_UNINHABITABLE_TITLE", "ART_10_UNINHABITABLE_BODY",
            "ART_10_RELOCATION_REQUEST_TITLE", "ART_10_RELOCATION_REQUEST_BODY",

            "ART_11_TITLE", "ART_11_BODY", "ART_11_HOURS",

            "ART_12_TITLE", "ART_12_BODY", "ART_12_DAYS",

            "ART_13_TITLE", "ART_13_LI1", "ART_13_LI2", "ART_13_LI3_EDIGITAL",

            "UNIT_PER_MONTH", "UNIT_VND_PER_MONTH", "UNIT_WATER", "UNIT_VND"

    );

    private String structureLabel(String enumName, String lang) {

        if (enumName == null || enumName.isBlank()) return "—";

        String vi = switch (enumName) {

            case "LEVEL_4" -> "Nhà cấp 4";

            case "TUBE_HOUSE" -> "Nhà ống";

            case "TOWN_HOUSE" -> "Nhà phố";

            case "VILLA" -> "Biệt thự";

            case "OTHER" -> "Khác";

            default -> enumName;

        };

        return "vi".equals(lang) ? vi : vi;

    }

    private String renderCoTenantsBlockVi(List<CoTenantDto> coTenants) {

        return renderCoTenantsBlock(coTenants, "vi");

    }

    public String renderCoTenantsBlock(List<CoTenantDto> coTenants, String lang) {
        if (coTenants == null || coTenants.isEmpty()) return "";

        boolean anyForeign = coTenants.stream()
                .anyMatch(c -> c.identityType() == com.isums.contractservice.domains.enums.CoTenantIdentityType.PASSPORT);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"clause-title\">").append(coTenantTitle(lang)).append("</div>");
        sb.append("<table class=\"assets\"><thead><tr>")
                .append("<th>").append(colName(lang)).append("</th>")
                .append("<th>").append(colIdKind(lang)).append("</th>")
                .append("<th>").append(colIdNo(lang)).append("</th>")
                .append("<th>").append(colRel(lang)).append("</th>")
                .append("<th>").append(colPhone(lang)).append("</th>");
        if (anyForeign) {
            sb.append("<th>").append(colPassport(lang)).append("</th>")
              .append("<th>").append(colVisa(lang)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");

        for (CoTenantDto c : coTenants) {
            sb.append("<tr>")
                    .append("<td>").append(esc(c.fullName())).append("</td>")
                    .append("<td>").append(esc(identityKindLabel(c.identityType(), lang))).append("</td>")
                    .append("<td>").append(esc(c.identityNumber())).append("</td>")
                    .append("<td>").append(esc(translateRelationship(c.relationship(), lang))).append("</td>")
                    .append("<td>").append(esc(nz(c.phoneNumber()))).append("</td>");
            if (anyForeign) {

                sb.append("<td>").append(esc(nzDash(c.passportNumber()))).append("</td>");
                String visaCell;
                if (c.identityType() == com.isums.contractservice.domains.enums.CoTenantIdentityType.PASSPORT) {
                    visaCell = ContractI18n.visa(c.visaType(),
                            c.visaExpiryDate() != null ? c.visaExpiryDate().toString() : null,
                            lang);
                } else {
                    visaCell = "—";
                }
                sb.append("<td>").append(esc(visaCell)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private static String colPassport(String l) { return "ja".equals(l) ? "旅券番号" : "en".equals(l) ? "Passport" : "Hộ chiếu"; }
    private static String colVisa(String l)     { return "ja".equals(l) ? "ビザ"     : "en".equals(l) ? "Visa"     : "Thị thực"; }
    private static String nzDash(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    private static String coTenantTitle(String lang) {

        return switch (lang == null ? "vi" : lang) {

            case "ja" -> "同居人（一時居住登録）";

            case "en" -> "CO-TENANTS (TEMPORARY RESIDENCE)";

            default   -> "NGƯỜI Ở CÙNG (ĐĂNG KÝ TẠM TRÚ)";

        };

    }

    private static String colName(String l)   { return "ja".equals(l) ? "氏名"       : "en".equals(l) ? "Full name" : "Họ tên"; }

    private static String colIdKind(String l) { return "ja".equals(l) ? "証明書種類" : "en".equals(l) ? "ID type"   : "Loại GT"; }

    private static String colIdNo(String l)   { return "ja".equals(l) ? "証明書番号" : "en".equals(l) ? "ID number" : "Số giấy tờ"; }

    private static String colRel(String l)    { return "ja".equals(l) ? "関係"       : "en".equals(l) ? "Relation"  : "Quan hệ"; }

    private static String colPhone(String l)  { return "ja".equals(l) ? "電話"       : "en".equals(l) ? "Phone"     : "SĐT"; }

    private static String identityKindLabel(Object kind, String lang) {

        if (kind == null) return "";

        String v = String.valueOf(kind).toUpperCase();

        return switch (v) {

            case "CCCD"     -> "ja".equals(lang) ? "CCCD（市民証）" : "en".equals(lang) ? "Citizen ID" : "CCCD";

            case "CMND"     -> "ja".equals(lang) ? "CMND（旧身分証）" : "en".equals(lang) ? "Old ID"    : "CMND";

            case "PASSPORT" -> "ja".equals(lang) ? "パスポート"     : "en".equals(lang) ? "Passport"  : "Hộ chiếu";

            default         -> v;

        };

    }

    private String translateRelationship(String raw, String lang) {

        if (raw == null || raw.isBlank()) return "";

        if ("vi".equals(lang)) return raw;

        String key = raw.trim().toLowerCase();

        String canned = switch (key) {

            case "friend", "bạn", "bạn bè"       -> "ja".equals(lang) ? "友人"   : "Friend";

            case "colleague", "đồng nghiệp"       -> "ja".equals(lang) ? "同僚"   : "Colleague";

            case "family", "gia đình"            -> "ja".equals(lang) ? "家族"   : "Family";

            case "spouse", "vợ", "chồng", "vợ/chồng" -> "ja".equals(lang) ? "配偶者" : "Spouse";

            case "child", "con"                   -> "ja".equals(lang) ? "子供"   : "Child";

            case "parent", "cha", "mẹ", "bố/mẹ"  -> "ja".equals(lang) ? "親"     : "Parent";

            case "sibling", "anh", "chị", "em", "anh/chị/em" -> "ja".equals(lang) ? "兄弟姉妹" : "Sibling";

            case "roommate", "bạn cùng phòng"    -> "ja".equals(lang) ? "ルームメイト" : "Roommate";

            default -> null;

        };

        if (canned != null) return canned;

        return translate(raw, lang);

    }

    private String translate(String text, String targetLang) {

        if (text == null || text.isBlank()) return "";

        if ("vi".equals(targetLang)) return text;

        return translationService.translate(text, "vi", targetLang);

    }

    private String translateOrNa(String text, String targetLang) {

        if (text == null || text.isBlank()) return ContractI18n.notApplicable(targetLang);

        return translate(text, targetLang);

    }

    private String translateNationality(String viNationality, String targetLang) {

        if (viNationality == null || viNationality.isBlank()) return ContractI18n.notApplicable(targetLang);

        if ("vi".equals(targetLang)) return viNationality;

        String normalized = java.text.Normalizer.normalize(viNationality.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace("đ", "d");

        java.util.Map<String, String[]> table = java.util.Map.ofEntries(
                java.util.Map.entry("viet nam",   new String[]{"Vietnam",        "ベトナム"}),
                java.util.Map.entry("vietnam",    new String[]{"Vietnam",        "ベトナム"}),
                java.util.Map.entry("nhat ban",   new String[]{"Japan",          "日本"}),
                java.util.Map.entry("nhat",       new String[]{"Japan",          "日本"}),
                java.util.Map.entry("han quoc",   new String[]{"South Korea",    "韓国"}),
                java.util.Map.entry("trieu tien", new String[]{"North Korea",    "北朝鮮"}),
                java.util.Map.entry("trung quoc", new String[]{"China",          "中国"}),
                java.util.Map.entry("dai loan",   new String[]{"Taiwan",         "台湾"}),
                java.util.Map.entry("hong kong",  new String[]{"Hong Kong",      "香港"}),
                java.util.Map.entry("singapore",  new String[]{"Singapore",      "シンガポール"}),
                java.util.Map.entry("malaysia",   new String[]{"Malaysia",       "マレーシア"}),
                java.util.Map.entry("thai lan",   new String[]{"Thailand",       "タイ"}),
                java.util.Map.entry("indonesia",  new String[]{"Indonesia",      "インドネシア"}),
                java.util.Map.entry("philippines",new String[]{"Philippines",    "フィリピン"}),
                java.util.Map.entry("an do",      new String[]{"India",          "インド"}),
                java.util.Map.entry("my",         new String[]{"United States",  "アメリカ合衆国"}),
                java.util.Map.entry("hoa ky",     new String[]{"United States",  "アメリカ合衆国"}),
                java.util.Map.entry("anh",        new String[]{"United Kingdom", "イギリス"}),
                java.util.Map.entry("phap",       new String[]{"France",         "フランス"}),
                java.util.Map.entry("duc",        new String[]{"Germany",        "ドイツ"}),
                java.util.Map.entry("nga",        new String[]{"Russia",         "ロシア"}),
                java.util.Map.entry("uc",         new String[]{"Australia",      "オーストラリア"}),
                java.util.Map.entry("canada",     new String[]{"Canada",         "カナダ"})
        );

        String[] mapped = table.get(normalized);
        if (mapped != null) {
            return "ja".equals(targetLang) ? mapped[1] : mapped[0];
        }

        return translate(viNationality, targetLang);

    }

    private String formatTenantIdIssue(CreateEContractRequest req, String lang) {

        boolean isForeigner = req.tenantTypeOrDefault() == TenantType.FOREIGNER;

        String datePart;

        String placePart;

        if (isForeigner) {

            datePart = req.passportIssueDate() != null

                    ? formatDate(req.passportIssueDate(), lang) + " " : "";

            placePart = "vi".equals(lang)

                    ? nz(req.passportIssuePlace())

                    : translateOrNa(req.passportIssuePlace(), lang);

        } else {

            datePart = req.dateOfIssue() != null

                    ? formatDate(req.dateOfIssue(), lang) + " " : "";

            placePart = "vi".equals(lang)

                    ? nz(req.placeOfIssue())

                    : translateOrNa(req.placeOfIssue(), lang);

        }

        return datePart + placePart;

    }

    private static String nz(String s) {

        return s == null ? "" : s;

    }

    private static String blankDash(String s) {

        return (s == null || s.isBlank()) ? "—" : s;

    }

    private static String defaultNationality(CreateEContractRequest req) {

        String explicit = req.nationality();

        if (explicit != null && !explicit.isBlank()) return explicit;

        return req.tenantTypeOrDefault() == TenantType.VIETNAMESE ? "Việt Nam" : "—";

    }

    private static String formatTenantVisa(CreateEContractRequest req, String lang) {

        if (req.tenantTypeOrDefault() == TenantType.VIETNAMESE) {

            return ContractI18n.notApplicable(lang);

        }

        String type = req.visaType();

        if (type == null || type.isBlank()) return "—";

        if (req.visaExpiryDate() == null) return type;

        String until = "ja".equals(lang) ? "有効期限"

                      : "en".equals(lang) ? "valid until"

                      : "hạn";

        String dateStr = formatDate(req.visaExpiryDate(), lang);

        return type + " — " + until + " " + dateStr;

    }

    private static String esc(String s) {

        if (s == null) return "";

        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

    }

    private static String render(String tmpl, Map<String, Object> data) {

        Matcher m = PLACEHOLDER.matcher(tmpl);

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

    private static int resolveForceMajeureHours(CreateEContractRequest req, LandlordProfile landlord) {
        if (req.forceMajeureNoticeHours() != null) return req.forceMajeureNoticeHours();
        if (landlord != null && landlord.getForceMajeureNoticeHours() != null) {
            return landlord.getForceMajeureNoticeHours();
        }
        return 24;
    }

    public record BuildInput(

            CreateEContractRequest request,

            LandlordProfile landlord,

            String propertyAddress,

            String propertyAreaM2,

            String propertyStructure,

            String houseLandCertNumber,

            String houseLandCertIssueDate,

            String houseLandCertIssuer,

            String assetsTableHtml,

            String assetsTableHtmlXx,

            String coTenantsBlockHtml,

            String coTenantsBlockHtmlXx,

            Map<String, Object> meters,

            ContractLanguage language,

            UUID contractId,

            ReplacementContext replacement,

            String documentNoOverride

    ) {

        public BuildInput {

            if (meters == null) meters = Map.of();

            if (propertyAreaM2 == null) propertyAreaM2 = "";

            if (propertyStructure == null) propertyStructure = "";

            if (houseLandCertNumber == null) houseLandCertNumber = "";

            if (houseLandCertIssueDate == null) houseLandCertIssueDate = "";

            if (houseLandCertIssuer == null) houseLandCertIssuer = "";

            if (assetsTableHtml == null) assetsTableHtml = "";

            if (assetsTableHtmlXx == null) assetsTableHtmlXx = assetsTableHtml;

            if (coTenantsBlockHtml == null) coTenantsBlockHtml = "";

            if (coTenantsBlockHtmlXx == null) coTenantsBlockHtmlXx = coTenantsBlockHtml;

        }

    }

}
