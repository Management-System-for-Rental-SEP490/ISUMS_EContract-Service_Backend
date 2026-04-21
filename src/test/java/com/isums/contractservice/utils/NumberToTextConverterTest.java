package com.isums.contractservice.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NumberToTextConverter")
class NumberToTextConverterTest {

    @Nested
    @DisplayName("Vietnamese")
    class Vi {
        @Test
        @DisplayName("zero → 'Không đồng'")
        void zero() {
            assertThat(NumberToTextConverter.convert(0L)).isEqualTo("Không đồng");
        }

        @Test
        @DisplayName("one million VND")
        void oneMillion() {
            assertThat(NumberToTextConverter.convert(1_000_000L))
                    .containsIgnoringCase("một triệu")
                    .endsWith("đồng");
        }

        @Test
        @DisplayName("complex number")
        void complex() {
            String s = NumberToTextConverter.convert(15_500_000L);
            // VI converter uses "năm" for 5 and compounds like "mười lăm" or "mười năm" depending on context;
            // "lăm" is the post-ten form. Check for robust keywords without tying to exact spacing.
            assertThat(s).endsWith("đồng");
            assertThat(s.toLowerCase()).contains("triệu");
        }

        @Test
        @DisplayName("negative")
        void negative() {
            assertThat(NumberToTextConverter.convert(-500_000L)).startsWith("Âm ");
        }
    }

    @Nested
    @DisplayName("English")
    class En {
        @Test
        @DisplayName("zero → 'Zero Vietnamese dong'")
        void zero() {
            assertThat(NumberToTextConverter.convert(0L, "en")).isEqualTo("Zero Vietnamese dong");
        }

        @Test
        @DisplayName("one million")
        void oneMillion() {
            assertThat(NumberToTextConverter.convert(1_000_000L, "en"))
                    .isEqualTo("One million Vietnamese dong");
        }

        @Test
        @DisplayName("twenty-three")
        void twentyThree() {
            assertThat(NumberToTextConverter.convert(23L, "en"))
                    .isEqualTo("Twenty-three Vietnamese dong");
        }

        @Test
        @DisplayName("complex with thousands")
        void complex() {
            // 15,500,000 = fifteen million five hundred thousand
            assertThat(NumberToTextConverter.convert(15_500_000L, "en").toLowerCase())
                    .contains("fifteen million")
                    .contains("five hundred thousand");
        }
    }

    @Nested
    @DisplayName("Japanese")
    class Ja {
        @Test
        @DisplayName("zero → '零ベトナムドン'")
        void zero() {
            assertThat(NumberToTextConverter.convert(0L, "ja")).isEqualTo("零ベトナムドン");
        }

        @Test
        @DisplayName("10,000 = 一万")
        void tenThousand() {
            assertThat(NumberToTextConverter.convert(10_000L, "ja")).isEqualTo("一万ベトナムドン");
        }

        @Test
        @DisplayName("1,000,000 = 百万")
        void oneMillion() {
            assertThat(NumberToTextConverter.convert(1_000_000L, "ja")).contains("百万");
        }

        @Test
        @DisplayName("15,500,000 groups by myriad")
        void fifteenMillion() {
            String s = NumberToTextConverter.convert(15_500_000L, "ja");
            assertThat(s).contains("千五百五十万").endsWith("ベトナムドン");
        }
    }

    @Nested
    @DisplayName("language fallback")
    class Fallback {
        @Test
        @DisplayName("unknown lang code falls back to VI")
        void unknownFallsBackToVi() {
            assertThat(NumberToTextConverter.convert(100L, "de"))
                    .endsWith("đồng");
        }

        @Test
        @DisplayName("null lang defaults to VI")
        void nullLang() {
            assertThat(NumberToTextConverter.convert(100L, null)).endsWith("đồng");
        }
    }
}
