package com.isums.contractservice.utils;

import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.domains.enums.PetPolicy;
import com.isums.contractservice.domains.enums.SmokingPolicy;
import com.isums.contractservice.domains.enums.SubleasePolicy;
import com.isums.contractservice.domains.enums.TaxResponsibility;
import com.isums.contractservice.domains.enums.TempResidenceRegisterBy;
import com.isums.contractservice.domains.enums.TenantType;
import com.isums.contractservice.domains.enums.VisitorPolicy;

import java.util.Map;

/**
 * Static translations for boilerplate text used by the bilingual lease-house
 * template. Three-language registry: vi (source) / en / ja.
 *
 * Rationale: legal boilerplate must render with canonical wording. Routing
 * these strings through AWS Translate at render time would introduce drift
 * across contracts and could surface awkward phrasings. User-supplied free
 * text (purpose, dispute forum, etc.) still goes through AWS Translate.
 */
public final class ContractI18n {

    private ContractI18n() {}

    public static String lang(ContractLanguage cl) {
        if (cl == null || cl == ContractLanguage.VI) return "vi";
        return cl == ContractLanguage.VI_JA ? "ja" : "en";
    }

    // =========================================================================
    // Boilerplate strings used in template (key → {vi, en, ja})
    // =========================================================================
    private static final Map<String, Map<String, String>> BOILER = Map.ofEntries(
            // Headings
            entry("HEADING_COUNTRY", "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
                    "THE SOCIALIST REPUBLIC OF VIETNAM",
                    "ベトナム社会主義共和国"),
            entry("HEADING_MOTTO", "Độc lập – Tự do – Hạnh phúc",
                    "Independence – Freedom – Happiness",
                    "独立・自由・幸福"),
            entry("HEADING_TITLE", "HỢP ĐỒNG THUÊ NHÀ Ở",
                    "RESIDENTIAL LEASE AGREEMENT",
                    "住宅賃貸借契約"),
            entry("HEADING_NO", "Số:", "No.:", "番号:"),

            // Legal basis
            entry("LEGAL_BASIS", "Căn cứ pháp lý", "Legal basis", "法的根拠"),
            entry("LAW_CIVIL",
                    "Bộ luật Dân sự 2015 (Điều 472–482; Điều 408).",
                    "Civil Code 2015 (Articles 472–482; Article 408).",
                    "民法2015（第472条〜第482条、第408条）。"),
            entry("LAW_HOUSING",
                    "Luật Nhà ở 2023 (Điều 163).",
                    "Law on Housing 2023 (Article 163).",
                    "住宅法2023（第163条）。"),
            entry("LAW_REALESTATE",
                    "Luật Kinh doanh bất động sản 2023.",
                    "Law on Real Estate Business 2023.",
                    "不動産事業法2023。"),
            entry("LAW_RESIDENCE",
                    "Luật Cư trú 2020.",
                    "Law on Residence 2020.",
                    "居住法2020。"),
            entry("LAW_PARTY_AGREEMENT",
                    "Thỏa thuận giữa các bên.",
                    "Agreement between the parties.",
                    "両当事者間の合意。"),

            // Section labels
            entry("LABEL_LANDLORD", "Bên cho thuê (Bên A)", "Lessor (Party A)", "賃貸人（甲）"),
            entry("LABEL_TENANT", "Bên thuê (Bên B)", "Lessee (Party B)", "賃借人（乙）"),
            entry("LABEL_FULL_NAME", "Họ và tên:", "Full name:", "氏名:"),
            entry("LABEL_DOB", "Ngày sinh:", "Date of birth:", "生年月日:"),
            entry("LABEL_ID", "CCCD/CMND:", "National ID:", "身分証番号:"),
            entry("LABEL_ID_ISSUE", "Ngày cấp / Nơi cấp:", "Issue date / place:", "発行日／発行場所:"),
            entry("LABEL_PERM_ADDRESS", "Địa chỉ thường trú:", "Permanent address:", "恒久住所:"),
            entry("LABEL_PHONE_EMAIL", "Điện thoại / Email:", "Phone / Email:", "電話／Eメール:"),
            entry("LABEL_TAX_CODE", "MST:", "Tax code:", "納税者番号:"),
            entry("LABEL_BANK", "Tài khoản:", "Bank account:", "銀行口座:"),
            entry("LABEL_IDENTITY_KIND", "Loại giấy tờ:", "Identity document:", "身分証明書:"),
            entry("LABEL_NATIONALITY", "Quốc tịch:", "Nationality:", "国籍:"),
            entry("LABEL_OCCUPATION", "Nghề nghiệp:", "Occupation:", "職業:"),
            entry("LABEL_DOB_GENDER", "Ngày sinh / Giới tính:", "DOB / Gender:", "生年月日／性別:"),
            entry("LABEL_VISA", "Thị thực:", "Visa:", "ビザ:"),
            entry("LABEL_PET", "Thú cưng:", "Pets:", "ペット:"),
            entry("LABEL_SMOKING", "Hút thuốc:", "Smoking:", "喫煙:"),
            entry("LABEL_SUBLEASE", "Cho thuê lại:", "Subletting:", "転貸:"),
            entry("LABEL_VISITOR", "Khách qua đêm:", "Overnight visitors:", "宿泊客:"),
            entry("LABEL_TEMP_RES", "Đăng ký tạm trú:", "Temporary residence registration:", "一時居住登録:"),
            entry("LAND_CERT_ISSUED_BY", "cấp ngày X bởi Y", "issued on", "発行日"),

            // ART_1 — Subject matter
            entry("ART_1_TITLE", "Điều 1. Đối tượng hợp đồng",
                    "Article 1. Subject matter",
                    "第1条 目的物"),
            entry("ART_1_P1",
                    "Bên A đồng ý cho Bên B thuê toàn bộ Nhà ở tại địa chỉ:",
                    "Party A agrees to lease to Party B the entire residential property at:",
                    "甲は乙に対し、次の住所にある住宅全体を賃貸することに同意する："),
            entry("ART_1_P2_AREA",
                    "Diện tích sử dụng:",
                    "Usable area:",
                    "使用面積:"),
            entry("ART_1_P2_STRUCTURE",
                    "kết cấu:",
                    "structure:",
                    "構造:"),
            entry("ART_1_P2_PURPOSE",
                    "mục đích:",
                    "purpose:",
                    "目的:"),
            entry("ART_1_P3_CERT",
                    "Chứng nhận quyền SH/SD:",
                    "Certificate of ownership/use rights:",
                    "所有権／使用権証明書:"),

            // ART_2 — Term
            entry("ART_2_TITLE", "Điều 2. Thời hạn thuê",
                    "Article 2. Lease term",
                    "第2条 賃貸期間"),
            entry("ART_2_FROM", "Từ ngày", "From", "開始日:"),
            entry("ART_2_TO", "đến hết ngày", "through", "終了日:"),
            entry("ART_2_DAYS", "ngày", "days", "日"),
            entry("ART_2_RENEWAL",
                    "Gia hạn phải được lập thành văn bản trước",
                    "Renewal must be agreed in writing at least",
                    "更新は書面で少なくとも"),

            // ART_3 — Rent & payment
            entry("ART_3_TITLE", "Điều 3. Giá thuê và thanh toán",
                    "Article 3. Rent and payment",
                    "第3条 賃料及び支払"),
            entry("ART_3_RENT", "Giá thuê:", "Monthly rent:", "月額賃料:"),
            entry("ART_3_TAX", "Thuế/phí:", "Taxes/fees:", "税金・手数料:"),
            entry("ART_3_TAX_RESP", "Trách nhiệm thuế TNCN:", "Personal income tax responsibility:", "個人所得税の負担:"),
            entry("ART_3_CYCLE", "Kỳ thanh toán:", "Payment cycle:", "支払サイクル:"),
            entry("ART_3_PAY_DAY", "ngày trả hằng kỳ:", "payment day:", "支払日:"),
            entry("ART_3_LATE",
                    "Chậm quá",
                    "Late payment exceeding",
                    "支払遅延が"),
            entry("ART_3_DAYS", "ngày", "days", "日を超える場合"),

            // ART_4 — Deposit
            entry("ART_4_TITLE", "Điều 4. Đặt cọc",
                    "Article 4. Security deposit",
                    "第4条 敷金"),
            entry("ART_4_AMOUNT", "Đặt cọc:", "Deposit amount:", "敷金額:"),
            entry("ART_4_PAID_ON", "nộp ngày", "paid on", "納付日:"),
            entry("ART_4_REFUND",
                    "Hoàn cọc trong",
                    "Deposit refunded within",
                    "敷金返還期限:"),
            entry("ART_4_DAYS", "ngày sau thanh lý.", "days after settlement.", "日以内（契約清算後）。"),

            // ART_5 — Handover
            entry("ART_5_TITLE", "Điều 5. Bàn giao",
                    "Article 5. Handover",
                    "第5条 引渡し"),
            entry("ART_5_HANDOVER",
                    "Bên A bàn giao Nhà thuê vào ngày",
                    "Party A hands over the Premises on",
                    "甲は次の日付に目的物を引き渡す:"),
            entry("ART_5_METER",
                    "Chỉ số công tơ đầu kỳ (chỉ để ghi nhận): điện",
                    "Initial meter readings (for record only): electricity",
                    "初期メーター値（記録のみ）: 電気"),

            // ART_6 — Utilities
            entry("ART_6_TITLE", "Điều 6. Dịch vụ điện, nước, internet",
                    "Article 6. Utilities",
                    "第6条 電気・水道・インターネット"),
            entry("ART_6_BODY",
                    "Full-house lease. Party B registers and pays utility bills directly with the providers. " +
                            "Party A does not charge utilities through this contract.",
                    "Full-house lease. Party B registers and pays utility bills directly with the providers. " +
                            "Party A does not charge utilities through this contract.",
                    "一棟貸し。乙は電気・水道・インターネット等を供給事業者と直接契約し支払う。" +
                            "甲は本契約を通じてこれらを徴収しない。"),

            // ART_7 — House rules (items inline in template using LABEL_*/POLICY values)
            entry("ART_7_TITLE", "Điều 7. Nội quy và quyền cư trú",
                    "Article 7. House rules and residence",
                    "第7条 ハウスルール及び居住"),

            // ART_8 — Landlord rights & duties
            entry("ART_8_TITLE", "Điều 8. Quyền và nghĩa vụ Bên A",
                    "Article 8. Lessor's rights and obligations",
                    "第8条 甲の権利義務"),
            entry("ART_8_LI1",
                    "Bàn giao Nhà thuê đúng hạn, đúng hiện trạng.",
                    "Deliver the Premises on time and in the agreed condition.",
                    "合意された条件で期限内に引き渡すこと。"),
            entry("ART_8_LI2",
                    "Bảo trì phần kết cấu và hạ tầng thuộc trách nhiệm chủ nhà.",
                    "Maintain structural parts and infrastructure for which the owner is responsible.",
                    "所有者が責任を負う構造部分及び設備を維持管理すること。"),
            entry("ART_8_LI3",
                    "Thông báo trước",
                    "Give at least",
                    "少なくとも"),
            entry("ART_8_DAYS",
                    "ngày khi kiểm tra/bảo trì.",
                    "days' notice before any inspection/maintenance.",
                    "日前に点検・保守を通知すること。"),
            entry("ART_8_LI4",
                    "Phát hành chứng từ thu tiền thuê theo yêu cầu hợp lệ.",
                    "Issue rent receipts upon legitimate request.",
                    "正当な要求があれば賃料受領書を発行すること。"),

            // ART_9 — Tenant rights & duties
            entry("ART_9_TITLE", "Điều 9. Quyền và nghĩa vụ Bên B",
                    "Article 9. Lessee's rights and obligations",
                    "第9条 乙の権利義務"),
            entry("ART_9_LI1",
                    "Sử dụng đúng mục đích, không tự ý thay đổi kết cấu.",
                    "Use the Premises for the stated purpose; do not alter the structure without approval.",
                    "使用目的に従って使用し、承認なく構造を変更しないこと。"),
            entry("ART_9_LI2",
                    "Giữ gìn tài sản; bồi thường thiệt hại do lỗi của mình.",
                    "Keep the property in good condition; compensate for damage caused by the Lessee's fault.",
                    "財産を善良に維持し、自己の過失による損害を賠償すること。"),
            entry("ART_9_LI3",
                    "Thanh toán đầy đủ, đúng hạn; tuân thủ PCCC và an ninh trật tự.",
                    "Pay in full and on time; comply with fire-safety and public-order rules.",
                    "期限通り全額を支払い、防火及び公共秩序の規則に従うこと。"),
            entry("ART_9_LI4",
                    "Tuân thủ pháp luật cư trú và nội quy tại Điều 7.",
                    "Comply with residence laws and the house rules in Article 7.",
                    "居住法及び第7条のハウスルールに従うこと。"),

            // ART_10 — Termination
            entry("ART_10_TITLE", "Điều 10. Chấm dứt hợp đồng",
                    "Article 10. Termination",
                    "第10条 解除"),
            entry("ART_10_LI1",
                    "Hết thời hạn và không gia hạn.",
                    "Expiration of the term without renewal.",
                    "期間満了かつ更新なきとき。"),
            entry("ART_10_LI2",
                    "Vi phạm nghĩa vụ cơ bản và không khắc phục trong",
                    "Material breach not cured within",
                    "重大な義務違反が"),
            entry("ART_10_LI3",
                    "Chậm thanh toán quá",
                    "Payment overdue by more than",
                    "支払遅延が連続して"),
            entry("ART_10_LI4",
                    "Bất khả kháng theo Điều 11.",
                    "Force majeure under Article 11.",
                    "第11条に基づく不可抗力。"),
            entry("ART_10_DAYS", "ngày.", "days.", "日を超える場合。"),
            entry("ART_10_A_FAULT",
                    "Lỗi Bên A:",
                    "Lessor's fault:",
                    "甲の過失:"),
            entry("ART_10_B_FAULT",
                    "Lỗi Bên B:",
                    "Lessee's fault:",
                    "乙の過失:"),

            // ART_11 — Force majeure
            entry("ART_11_TITLE", "Điều 11. Bất khả kháng",
                    "Article 11. Force majeure",
                    "第11条 不可抗力"),
            entry("ART_11_BODY",
                    "Bên gặp sự kiện BKK phải thông báo trong",
                    "The affected party must notify the other within",
                    "不可抗力に遭遇した当事者は相手方に"),
            entry("ART_11_HOURS",
                    "giờ. Hai bên thỏa thuận tạm dừng/giảm/chấm dứt hợp đồng theo nguyên tắc thiện chí.",
                    "hours. The parties shall agree in good faith to suspend, reduce or terminate.",
                    "時間以内に通知すること。両当事者は誠実に中止・減額・解除を協議する。"),

            // ART_12 — Disputes
            entry("ART_12_TITLE", "Điều 12. Giải quyết tranh chấp",
                    "Article 12. Dispute resolution",
                    "第12条 紛争解決"),
            entry("ART_12_BODY",
                    "Ưu tiên thương lượng. Không đạt kết quả trong",
                    "Negotiation first. Failing resolution within",
                    "まず協議による。"),
            entry("ART_12_DAYS",
                    "ngày: giải quyết tại",
                    "days, the dispute shall be submitted to",
                    "日以内に解決しない場合、次の機関にて解決する:"),

            // ART_13 — General
            entry("ART_13_TITLE", "Điều 13. Điều khoản chung",
                    "Article 13. General provisions",
                    "第13条 一般条項"),
            entry("ART_13_LI1",
                    "Hiệu lực từ ngày ký hoặc",
                    "Effective upon signing or on",
                    "本契約は署名日又は"),
            entry("ART_13_LI2",
                    "Phụ lục và biên bản bàn giao là phần không tách rời.",
                    "Appendices and the handover record form an integral part hereof.",
                    "附属書及び引渡書は本契約の不可分の一部を構成する。"),
            entry("ART_13_LI3_A",
                    "Lập",
                    "Executed in",
                    "本契約は"),
            entry("ART_13_LI3_B",
                    "bản, mỗi bên giữ",
                    "counterparts; each party retains",
                    "部作成され、各当事者が"),
            entry("ART_13_LI3_C",
                    "bản, giá trị pháp lý như nhau.",
                    "counterparts with equal legal effect.",
                    "部を保持する。全て同一の法的効力を有する。"),

            entry("ART_13_LI3_EDIGITAL",
                    "Hợp đồng được ký điện tử qua hệ thống VNPT eContract theo Luật Giao dịch điện tử 2023. Mỗi bên nhận một bản PDF đã ký có giá trị pháp lý tương đương bản gốc.",
                    "This contract is electronically signed via VNPT eContract under the Law on Electronic Transactions 2023. Each party receives a signed PDF copy with equivalent legal force to the original.",
                    "本契約は電子取引法2023に基づきVNPT eContractシステムで電子署名されます。各当事者は原本と同等の法的効力を有する署名済PDFを受領します。"),

            // Prevailing language clause
            entry("PREVAILING_LANGUAGE_CLAUSE",
                    "Bản tiếng Việt có giá trị pháp lý ưu tiên khi có khác biệt giữa hai ngôn ngữ.",
                    "The Vietnamese version shall prevail in case of discrepancy between the two versions.",
                    "両言語間に相違がある場合はベトナム語版が優先する。")
    );

    public static String boilerplate(String key, String langCode) {
        Map<String, String> row = BOILER.get(key);
        if (row == null) throw new IllegalStateException("Unknown boilerplate key: " + key);
        String v = row.get(langCode);
        if (v == null) throw new IllegalStateException("Missing " + langCode + " for " + key);
        return v;
    }

    private static Map.Entry<String, Map<String, String>> entry(String key, String vi, String en, String ja) {
        return Map.entry(key, Map.of("vi", vi, "en", en, "ja", ja));
    }

    // =========================================================================
    // Enum → localized text
    // =========================================================================
    public static String petPolicy(PetPolicy p, String lang) {
        if (p == null) return "";
        return switch (p) {
            case ALLOWED -> pick(lang, "Được phép", "Allowed", "許可");
            case NOT_ALLOWED -> pick(lang, "Không cho phép", "Not allowed", "禁止");
            case ALLOWED_WITH_APPROVAL ->
                    pick(lang, "Cho phép khi có chấp thuận trước", "Allowed with prior approval", "事前承認にて許可");
        };
    }

    public static String smokingPolicy(SmokingPolicy p, String lang) {
        if (p == null) return "";
        return switch (p) {
            case ALLOWED -> pick(lang, "Được phép", "Allowed", "許可");
            case NOT_ALLOWED -> pick(lang, "Không hút thuốc trong nhà", "No smoking indoors", "室内禁煙");
            case OUTDOOR_ONLY -> pick(lang, "Chỉ hút ngoài trời", "Outdoor only", "屋外のみ可");
        };
    }

    public static String subleasePolicy(SubleasePolicy p, String lang) {
        if (p == null) return "";
        return switch (p) {
            case NOT_ALLOWED -> pick(lang, "Không cho thuê lại", "Subletting not allowed", "転貸禁止");
            case ALLOWED_WITH_WRITTEN_APPROVAL ->
                    pick(lang, "Cho phép khi có chấp thuận bằng văn bản", "Allowed with written approval", "書面承認により可");
        };
    }

    public static String visitorPolicy(VisitorPolicy p, String lang) {
        if (p == null) return "";
        return switch (p) {
            case UNRESTRICTED -> pick(lang, "Không hạn chế", "Unrestricted", "制限なし");
            case OVERNIGHT_NEEDS_APPROVAL ->
                    pick(lang, "Khách qua đêm cần báo trước", "Overnight guests require notice", "宿泊客は事前通知要");
            case NO_OVERNIGHT -> pick(lang, "Không được ở qua đêm", "No overnight guests", "宿泊禁止");
        };
    }

    public static String tempResidenceBy(TempResidenceRegisterBy t, String lang) {
        if (t == null) return "";
        return switch (t) {
            case LANDLORD -> pick(lang, "Bên A đăng ký", "Lessor registers", "甲が登録");
            case TENANT -> pick(lang, "Bên B tự đăng ký", "Lessee self-registers", "乙が自ら登録");
        };
    }

    public static String taxResponsibility(TaxResponsibility t, String lang) {
        if (t == null) return "";
        return switch (t) {
            case LANDLORD -> pick(lang, "Bên A chịu", "Lessor pays", "甲が負担");
            case TENANT -> pick(lang, "Bên B chịu", "Lessee pays", "乙が負担");
            case SHARED -> pick(lang, "Hai bên cùng chịu theo thỏa thuận",
                    "Shared between the parties by agreement",
                    "両当事者が合意に基づき分担");
        };
    }

    public static String tenantType(TenantType t, String lang) {
        if (t == null) return "";
        return switch (t) {
            case VIETNAMESE -> pick(lang, "Công dân Việt Nam", "Vietnamese citizen", "ベトナム国籍");
            case FOREIGNER -> pick(lang, "Người nước ngoài", "Foreign national", "外国籍");
        };
    }

    public static String identityKind(TenantType t, String lang) {
        if (t == TenantType.FOREIGNER) return pick(lang, "Hộ chiếu", "Passport", "パスポート");
        return pick(lang, "Căn cước công dân", "Citizen ID (CCCD)", "市民証（CCCD）");
    }

    public static String gender(String g, String lang) {
        if (g == null || g.isBlank()) return "";
        String norm = g.trim().toUpperCase();
        if (norm.startsWith("M") || norm.equals("NAM")) return pick(lang, "Nam", "Male", "男性");
        if (norm.startsWith("F") || norm.startsWith("W") || norm.equals("NỮ") || norm.equals("NU"))
            return pick(lang, "Nữ", "Female", "女性");
        return g;
    }

    // Words like "None" / "N/A" for missing optional data
    public static String notApplicable(String lang) {
        return pick(lang, "Không áp dụng", "N/A", "該当なし");
    }

    private static String pick(String lang, String vi, String en, String ja) {
        if (lang == null) return vi;
        return switch (lang) {
            case "en" -> en;
            case "ja" -> ja;
            default -> vi;
        };
    }
}
