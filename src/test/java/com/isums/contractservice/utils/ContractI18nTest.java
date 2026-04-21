package com.isums.contractservice.utils;

import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.domains.enums.PetPolicy;
import com.isums.contractservice.domains.enums.SmokingPolicy;
import com.isums.contractservice.domains.enums.SubleasePolicy;
import com.isums.contractservice.domains.enums.TaxResponsibility;
import com.isums.contractservice.domains.enums.TempResidenceRegisterBy;
import com.isums.contractservice.domains.enums.TenantType;
import com.isums.contractservice.domains.enums.VisitorPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ContractI18n")
class ContractI18nTest {

    @Nested
    @DisplayName("lang()")
    class LangCode {
        @Test
        @DisplayName("maps ContractLanguage to target code")
        void maps() {
            assertThat(ContractI18n.lang(ContractLanguage.VI)).isEqualTo("vi");
            assertThat(ContractI18n.lang(ContractLanguage.VI_EN)).isEqualTo("en");
            assertThat(ContractI18n.lang(ContractLanguage.VI_JA)).isEqualTo("ja");
        }

        @Test
        @DisplayName("null falls back to vi")
        void fallsBack() {
            assertThat(ContractI18n.lang(null)).isEqualTo("vi");
        }
    }

    @Nested
    @DisplayName("boilerplate()")
    class Boilerplate {
        @Test
        @DisplayName("returns vi wording")
        void vi() {
            assertThat(ContractI18n.boilerplate("HEADING_TITLE", "vi"))
                    .isEqualTo("HỢP ĐỒNG THUÊ NHÀ Ở");
            assertThat(ContractI18n.boilerplate("LEGAL_BASIS", "vi"))
                    .isEqualTo("Căn cứ pháp lý");
        }

        @Test
        @DisplayName("returns en wording")
        void en() {
            assertThat(ContractI18n.boilerplate("HEADING_TITLE", "en"))
                    .isEqualTo("RESIDENTIAL LEASE AGREEMENT");
            assertThat(ContractI18n.boilerplate("LAW_HOUSING", "en"))
                    .contains("Housing 2023");
        }

        @Test
        @DisplayName("returns ja wording")
        void ja() {
            assertThat(ContractI18n.boilerplate("HEADING_TITLE", "ja"))
                    .isEqualTo("住宅賃貸借契約");
            assertThat(ContractI18n.boilerplate("PREVAILING_LANGUAGE_CLAUSE", "ja"))
                    .contains("ベトナム語");
        }

        @Test
        @DisplayName("throws on unknown key")
        void unknownKey() {
            assertThatThrownBy(() -> ContractI18n.boilerplate("NONEXISTENT_KEY", "en"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unknown boilerplate key");
        }
    }

    @Nested
    @DisplayName("policy enum → localized")
    class PolicyI18n {
        @Test
        @DisplayName("PetPolicy covers all three values in all three languages")
        void petPolicy() {
            for (PetPolicy p : PetPolicy.values()) {
                assertThat(ContractI18n.petPolicy(p, "vi")).isNotBlank();
                assertThat(ContractI18n.petPolicy(p, "en")).isNotBlank();
                assertThat(ContractI18n.petPolicy(p, "ja")).isNotBlank();
            }
            assertThat(ContractI18n.petPolicy(PetPolicy.ALLOWED_WITH_APPROVAL, "en"))
                    .contains("approval");
        }

        @Test
        @DisplayName("SmokingPolicy OUTDOOR_ONLY renders in all languages")
        void smokingPolicy() {
            assertThat(ContractI18n.smokingPolicy(SmokingPolicy.OUTDOOR_ONLY, "en"))
                    .isEqualTo("Outdoor only");
            assertThat(ContractI18n.smokingPolicy(SmokingPolicy.OUTDOOR_ONLY, "ja"))
                    .contains("屋外");
        }

        @Test
        @DisplayName("SubleasePolicy ALLOWED_WITH_WRITTEN_APPROVAL renders")
        void subleasePolicy() {
            assertThat(ContractI18n.subleasePolicy(SubleasePolicy.ALLOWED_WITH_WRITTEN_APPROVAL, "en"))
                    .contains("written approval");
        }

        @Test
        @DisplayName("VisitorPolicy three values")
        void visitorPolicy() {
            for (VisitorPolicy v : VisitorPolicy.values()) {
                assertThat(ContractI18n.visitorPolicy(v, "en")).isNotBlank();
            }
            assertThat(ContractI18n.visitorPolicy(VisitorPolicy.NO_OVERNIGHT, "vi"))
                    .contains("qua đêm");
        }

        @Test
        @DisplayName("TaxResponsibility handles SHARED")
        void taxResponsibility() {
            assertThat(ContractI18n.taxResponsibility(TaxResponsibility.SHARED, "en"))
                    .contains("Shared");
            assertThat(ContractI18n.taxResponsibility(TaxResponsibility.SHARED, "ja"))
                    .contains("分担");
            assertThat(ContractI18n.taxResponsibility(TaxResponsibility.LANDLORD, "vi"))
                    .contains("Bên A");
        }

        @Test
        @DisplayName("TempResidenceRegisterBy")
        void tempResidence() {
            assertThat(ContractI18n.tempResidenceBy(TempResidenceRegisterBy.LANDLORD, "en"))
                    .isEqualTo("Lessor registers");
            assertThat(ContractI18n.tempResidenceBy(TempResidenceRegisterBy.TENANT, "ja"))
                    .contains("乙");
        }

        @Test
        @DisplayName("TenantType")
        void tenantType() {
            assertThat(ContractI18n.tenantType(TenantType.VIETNAMESE, "en"))
                    .isEqualTo("Vietnamese citizen");
            assertThat(ContractI18n.tenantType(TenantType.FOREIGNER, "ja"))
                    .isEqualTo("外国籍");
        }

        @Test
        @DisplayName("identityKind swaps by tenant type")
        void identityKind() {
            assertThat(ContractI18n.identityKind(TenantType.FOREIGNER, "en"))
                    .isEqualTo("Passport");
            assertThat(ContractI18n.identityKind(TenantType.VIETNAMESE, "ja"))
                    .contains("CCCD");
        }

        @Test
        @DisplayName("gender normalizes M/F across languages")
        void gender() {
            assertThat(ContractI18n.gender("M", "en")).isEqualTo("Male");
            assertThat(ContractI18n.gender("F", "ja")).isEqualTo("女性");
            assertThat(ContractI18n.gender("nam", "vi")).isEqualTo("Nam");
            assertThat(ContractI18n.gender("Nữ", "en")).isEqualTo("Female");
        }

        @Test
        @DisplayName("gender passes through unknown values")
        void genderUnknown() {
            assertThat(ContractI18n.gender("Non-binary", "en")).isEqualTo("Non-binary");
        }

        @Test
        @DisplayName("null enums return empty string")
        void nullEnums() {
            assertThat(ContractI18n.petPolicy(null, "en")).isEmpty();
            assertThat(ContractI18n.taxResponsibility(null, "ja")).isEmpty();
            assertThat(ContractI18n.visitorPolicy(null, "vi")).isEmpty();
        }
    }
}
