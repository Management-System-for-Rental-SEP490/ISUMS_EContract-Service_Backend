package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CoTenantDto;
import com.isums.contractservice.domains.dtos.CreateEContractRequest;
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

/**
 * Renders the lease-house contract HTML for the requested language.
 * VI → single-column template (LEASE_HOUSE_VI).
 * VI_EN / VI_JA → two-column bilingual template (LEASE_HOUSE_BILINGUAL).
 *
 * Boilerplate (article titles, labels, law citations) comes from {@link ContractI18n}
 * for canonical wording. User-supplied free text (purpose, dispute forum, penalties)
 * goes through {@link ContractTranslationService} — cached via hash so boilerplate-like
 * phrases are billed once across all contracts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractHtmlBuilder {

    public static final String CODE_VI = "LEASE_HOUSE_VI";
    public static final String CODE_BILINGUAL = "LEASE_HOUSE_BILINGUAL";
    // Legacy code kept for backward compatibility with existing contracts.
    public static final String CODE_LEGACY = "LEASE_HOUSE";

    private static final DateTimeFormatter DMY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Z0-9_][A-Z0-9_.]*)\\s*}}");

    private final EContractTemplateRepository templateRepo;
    private final ContractTranslationService translationService;

    public String build(BuildInput in) {
        ContractLanguage lang = in.language() != null ? in.language() : ContractLanguage.VI;
        if (lang == ContractLanguage.VI) {
            return renderVi(in);
        }
        return renderBilingual(in, lang);
    }

    // =========================================================================
    // VI-only rendering (legacy + new template both supported)
    // =========================================================================
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
        // Landlord
        data.put("LANDLORD_NAME", nz(lp.getFullName()));
        data.put("LANDLORD_ID", nz(lp.getIdentityNumber()));
        data.put("LANDLORD_DOB", lp.getDateOfBirth() != null ? lp.getDateOfBirth().toString() : "");
        data.put("LANDLORD_ID_ISSUE",
                (lp.getIdentityIssueDate() != null ? lp.getIdentityIssueDate() + " " : "")
                        + nz(lp.getIdentityIssuePlace()));
        data.put("LANDLORD_ADDRESS", nz(lp.getAddress()));
        data.put("LANDLORD_PERMANENT_ADDRESS", nz(lp.getPermanentAddress()));
        data.put("LANDLORD_PHONE", nz(lp.getPhoneNumber()));
        data.put("LANDLORD_EMAIL", nz(lp.getEmail()));
        data.put("LANDLORD_TAX_CODE", nz(lp.getTaxCode()));
        data.put("LANDLORD_BANK", nz(lp.getBankAccount()));
        data.put("LANDLORD_BANK_NAME", nz(lp.getBankName()));

        // Tenant
        data.put("TENANT_NAME", nz(req.name()));
        data.put("TENANT_ID", nz(req.identityNumber() != null ? req.identityNumber() : req.passportNumber()));
        data.put("TENANT_ID_ISSUE",
                (req.dateOfIssue() != null ? DMY.format(req.dateOfIssue()) + " " : "")
                        + nz(req.placeOfIssue()));
        data.put("TENANT_DOB", req.dateOfBirth() != null ? req.dateOfBirth().toString() : "");
        data.put("TENANT_GENDER", ContractI18n.gender(req.gender(), "vi"));
        data.put("TENANT_IDENTITY_KIND",
                ContractI18n.identityKind(req.tenantTypeOrDefault(), "vi"));
        data.put("TENANT_NATIONALITY", nz(req.nationality()));
        data.put("TENANT_OCCUPATION", nz(req.occupation()));
        data.put("TENANT_PERMANENT_ADDRESS", nz(req.permanentAddress()));
        data.put("TENANT_ADDRESS", nz(req.tenantAddress()));
        data.put("TENANT_PHONE", nz(req.phoneNumber()));
        data.put("TENANT_EMAIL", nz(req.email()));
        data.put("TENANT_VISA",
                req.visaType() != null
                        ? req.visaType() + (req.visaExpiryDate() != null ? " — hạn " + req.visaExpiryDate() : "")
                        : "");

        // Property — area + structure are facts about the house (from House-service
        // gRPC), purpose is fixed "để ở" per platform scope (whole-house lease).
        data.put("PROPERTY_ADDRESS", nz(propertyAddress));
        data.put("USABLE_AREA_M2", nz(in.propertyAreaM2()));
        data.put("STRUCTURE", structureLabel(in.propertyStructure(), "vi"));
        // GCN (land-use certificate) is a house fact — pulled from house gRPC
        // at render time, not stored on the contract.
        data.put("LAND_CERT_NUMBER", nz(in.houseLandCertNumber()));
        data.put("LAND_CERT_ISSUE_DATE", nz(in.houseLandCertIssueDate()));
        data.put("LAND_CERT_ISSUER", nz(in.houseLandCertIssuer()));

        // Dates
        data.put("START_DATE", DMY.format(req.startDate()));
        data.put("END_DATE", DMY.format(req.endDate()));
        data.put("RENEW_NOTICE_DAYS", req.renewNoticeDaysOrDefault());
        data.put("HANDOVER_DATE", DMY.format(req.handoverDate()));
        data.put("DEPOSIT_DATE", DMY.format(req.depositDate()));
        data.put("EFFECTIVE_DATE", DMY.format(Instant.now()));
        data.put("DOCUMENT_NO", "EC_" + System.currentTimeMillis());

        // Money
        data.put("RENT_AMOUNT", req.rentAmount());
        data.put("RENT_TEXT", NumberToTextConverter.convert(req.rentAmount()));
        data.put("DEPOSIT_AMOUNT", req.depositAmount());
        data.put("TAX_FEE_NOTE", req.taxFeeNoteOrDefault());
        data.put("PAY_CYCLE", req.payCycleOrDefault());
        data.put("PAY_DAY", req.payDate());
        data.put("LATE_DAYS", req.lateDaysOrDefault());
        data.put("LATE_PENALTY", req.latePenaltyPercentOrDefault());
        data.put("DEPOSIT_REFUND_DAYS", req.depositRefundDaysOrDefault());
        data.put("TAX_RESPONSIBILITY",
                ContractI18n.taxResponsibility(req.taxResponsibilityOrDefault(), "vi"));

        // Meters (optional)
        Map<String, Object> meters = in.meters();
        data.put("METER_ELECTRIC", meters.getOrDefault("electric", "—"));
        data.put("METER_WATER", meters.getOrDefault("water", "—"));
        data.put("METER_NOTE", meters.getOrDefault("note", ""));

        // Policies
        data.put("PET_POLICY", ContractI18n.petPolicy(req.petPolicyOrDefault(), "vi"));
        data.put("SMOKING_POLICY", ContractI18n.smokingPolicy(req.smokingPolicyOrDefault(), "vi"));
        data.put("SUBLEASE_POLICY", ContractI18n.subleasePolicy(req.subleasePolicyOrDefault(), "vi"));
        data.put("VISITOR_POLICY", ContractI18n.visitorPolicy(req.visitorPolicyOrDefault(), "vi"));
        data.put("TEMP_RESIDENCE_REGISTER_BY",
                ContractI18n.tempResidenceBy(req.tempResidenceRegisterByOrDefault(), "vi"));

        // Parties
        data.put("LANDLORD_NOTICE_DAYS", req.landlordNoticeDaysOrDefault());
        data.put("CURE_DAYS", req.cureDaysOrDefault());
        data.put("MAX_LATE_DAYS", req.maxLateDaysOrDefault());
        data.put("EARLY_TERMINATION_PENALTY", req.earlyTerminationPenaltyOrDefault());
        data.put("LANDLORD_BREACH_COMPENSATION", req.landlordBreachCompensationOrDefault());
        data.put("FORCE_MAJEURE_NOTICE_HOURS", req.forceMajeureNoticeHoursOrDefault());
        data.put("DISPUTE_DAYS", req.disputeDaysOrDefault());
        data.put("DISPUTE_FORUM", req.disputeForumOrDefault());
        data.put("PREVAILING_LANGUAGE_CLAUSE", ContractI18n.boilerplate("PREVAILING_LANGUAGE_CLAUSE", "vi"));

        // Co-tenants block
        data.put("CO_TENANTS_BLOCK", renderCoTenantsBlockVi(req.coTenants()));
        data.put("ASSETS_TABLE", in.assetsTableHtml());

        // Utility rules hint (legacy VN template uses UTILITY_RULES placeholder)
        data.put("UTILITY_RULES", "Theo thỏa thuận hai bên (thuê nguyên căn — bên B tự đăng ký, thanh toán)");

        return data;
    }

    // =========================================================================
    // Bilingual rendering
    // =========================================================================
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

        // All universal (non-suffixed) keys carry over from VI map.
        // The bilingual template re-uses names for shared data (numbers, dates, enums that are "just text").
        for (Map.Entry<String, Object> e : vi.entrySet()) {
            // Universal placeholders that appear unsuffixed on both sides of the template
            if (isUniversalKey(e.getKey())) data.put(e.getKey(), e.getValue());
        }

        // .vi variants — copy VI values
        for (String k : VI_XX_SPLIT_KEYS) {
            Object v = vi.get(k);
            if (v != null) data.put(k + ".vi", v);
        }

        // .xx variants — translate or compute target text
        data.put("RENT_TEXT.xx", NumberToTextConverter.convert(req.rentAmount(), xxCode));
        data.put("PAY_CYCLE.xx", translate(req.payCycleOrDefault(), xxCode));
        data.put("TAX_FEE_NOTE.xx", translate(req.taxFeeNoteOrDefault(), xxCode));
        data.put("TAX_RESPONSIBILITY.xx",
                ContractI18n.taxResponsibility(req.taxResponsibilityOrDefault(), xxCode));
        data.put("TENANT_IDENTITY_KIND.xx",
                ContractI18n.identityKind(req.tenantTypeOrDefault(), xxCode));
        data.put("TENANT_GENDER.xx", ContractI18n.gender(req.gender(), xxCode));
        data.put("TENANT_OCCUPATION.xx", translateOrNa(req.occupation(), xxCode));
        data.put("TENANT_PERMANENT_ADDRESS.xx", translateOrNa(req.permanentAddress(), xxCode));
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
        data.put("EFFECTIVE_DATE.xx", vi.get("EFFECTIVE_DATE"));
        data.put("DOCUMENT_NO.xx", vi.get("DOCUMENT_NO"));
        data.put("PREVAILING_LANGUAGE_CLAUSE.xx",
                ContractI18n.boilerplate("PREVAILING_LANGUAGE_CLAUSE", xxCode));

        // Boilerplate (ART_*, LABEL_*, LAW_*, HEADING_*, LAND_CERT_ISSUED_BY)
        for (String key : BOILERPLATE_KEYS) {
            data.put(key + ".xx", ContractI18n.boilerplate(key, xxCode));
        }

        return data;
    }

    // Keys that appear un-suffixed in the bilingual template (shared data)
    private static boolean isUniversalKey(String k) {
        return switch (k) {
            case "LANDLORD_NAME", "LANDLORD_ID", "LANDLORD_DOB", "LANDLORD_ID_ISSUE",
                 "LANDLORD_PERMANENT_ADDRESS", "LANDLORD_PHONE", "LANDLORD_EMAIL",
                 "LANDLORD_TAX_CODE", "LANDLORD_BANK", "LANDLORD_BANK_NAME",
                 "TENANT_NAME", "TENANT_ID", "TENANT_ID_ISSUE", "TENANT_DOB",
                 "TENANT_NATIONALITY", "TENANT_PHONE", "TENANT_EMAIL", "TENANT_VISA",
                 "PROPERTY_ADDRESS", "USABLE_AREA_M2",
                 "LAND_CERT_NUMBER", "LAND_CERT_ISSUE_DATE",
                 "START_DATE", "END_DATE", "HANDOVER_DATE", "DEPOSIT_DATE",
                 "RENT_AMOUNT", "DEPOSIT_AMOUNT",
                 "PAY_DAY", "LATE_DAYS", "LATE_PENALTY",
                 "DEPOSIT_REFUND_DAYS", "RENEW_NOTICE_DAYS", "LANDLORD_NOTICE_DAYS",
                 "CURE_DAYS", "MAX_LATE_DAYS", "FORCE_MAJEURE_NOTICE_HOURS",
                 "DISPUTE_DAYS",
                 "METER_ELECTRIC", "METER_WATER",
                 "CO_TENANTS_BLOCK", "ASSETS_TABLE" -> true;
            default -> false;
        };
    }

    // Keys that have both .vi and .xx copies in the bilingual template
    private static final List<String> VI_XX_SPLIT_KEYS = List.of(
            "STRUCTURE", "PAY_CYCLE", "RENT_TEXT",
            "TAX_FEE_NOTE", "TAX_RESPONSIBILITY",
            "TENANT_IDENTITY_KIND", "TENANT_GENDER",
            "TENANT_OCCUPATION", "TENANT_PERMANENT_ADDRESS",
            "PET_POLICY", "SMOKING_POLICY", "SUBLEASE_POLICY", "VISITOR_POLICY",
            "TEMP_RESIDENCE_REGISTER_BY",
            "EARLY_TERMINATION_PENALTY", "LANDLORD_BREACH_COMPENSATION",
            "DISPUTE_FORUM", "METER_NOTE",
            "LAND_CERT_ISSUER", "EFFECTIVE_DATE", "DOCUMENT_NO",
            "PREVAILING_LANGUAGE_CLAUSE"
    );

    // Keys that need only .xx (VI side has hard-coded wording in the template)
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
            "ART_11_TITLE", "ART_11_BODY", "ART_11_HOURS",
            "ART_12_TITLE", "ART_12_BODY", "ART_12_DAYS",
            "ART_13_TITLE", "ART_13_LI1", "ART_13_LI2", "ART_13_LI3_EDIGITAL"
    );

    // =========================================================================
    // Helpers
    // =========================================================================
    /**
     * Render the House-side structure enum (LEVEL_4, TUBE_HOUSE, ...) into the
     * VN phrase that ends up on the template. English enum values are hidden
     * from the tenant — they'd see "Nhà cấp 4" not "LEVEL_4".
     */
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
        if (coTenants == null || coTenants.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"clause-title\">NGƯỜI Ở CÙNG (ĐĂNG KÝ TẠM TRÚ)</div>");
        sb.append("<table><thead><tr>")
                .append("<th>Họ tên</th><th>Loại GT</th><th>Số giấy tờ</th>")
                .append("<th>Quan hệ</th><th>SĐT</th></tr></thead><tbody>");
        for (CoTenantDto c : coTenants) {
            sb.append("<tr>")
                    .append("<td>").append(esc(c.fullName())).append("</td>")
                    .append("<td>").append(esc(String.valueOf(c.identityType()))).append("</td>")
                    .append("<td>").append(esc(c.identityNumber())).append("</td>")
                    .append("<td>").append(esc(c.relationship())).append("</td>")
                    .append("<td>").append(esc(nz(c.phoneNumber()))).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
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

    private static String nz(String s) {
        return s == null ? "" : s;
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

    // =========================================================================
    // Input record
    // =========================================================================
    public record BuildInput(
            CreateEContractRequest request,
            LandlordProfile landlord,
            String propertyAddress,
            /** m² from houses.area_m2 — "" if not filled on house profile. */
            String propertyAreaM2,
            /** HouseStructure enum name from houses.structure — "" if not set. */
            String propertyStructure,
            /** GCN number from houses.land_cert_number — "" if not set. */
            String houseLandCertNumber,
            /** ISO-8601 date string from houses.land_cert_issue_date — "" if not set. */
            String houseLandCertIssueDate,
            /** Issuing authority from houses.land_cert_issuer — "" if not set. */
            String houseLandCertIssuer,
            String assetsTableHtml,
            Map<String, Object> meters,
            ContractLanguage language,
            UUID contractId
    ) {
        public BuildInput {
            if (meters == null) meters = Map.of();
            if (propertyAreaM2 == null) propertyAreaM2 = "";
            if (propertyStructure == null) propertyStructure = "";
            if (houseLandCertNumber == null) houseLandCertNumber = "";
            if (houseLandCertIssueDate == null) houseLandCertIssueDate = "";
            if (houseLandCertIssuer == null) houseLandCertIssuer = "";
        }
    }
}
