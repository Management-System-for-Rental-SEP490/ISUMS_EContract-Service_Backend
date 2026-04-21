package com.isums.contractservice.utils;

/**
 * Spells out a VND amount in Vietnamese, English, or Japanese.
 * Used to print amounts in words on the contract — required for legal
 * unambiguity when the numeric and written amounts must match.
 */
public class NumberToTextConverter {

    // ---- Vietnamese ----------------------------------------------------------
    private static final String[] UNITS = {"", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};
    private static final String[] TENS = {"", "mười", "hai mươi", "ba mươi", "bốn mươi", "năm mươi", "sáu mươi", "bảy mươi", "tám mươi", "chín mươi"};
    private static final String[] GROUPS = {"", "nghìn", "triệu", "tỷ"};

    // ---- English -------------------------------------------------------------
    private static final String[] EN_UNITS = {
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen"
    };
    private static final String[] EN_TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };
    private static final String[] EN_SCALES = {"", "thousand", "million", "billion", "trillion"};

    // ---- Japanese (legal style, 漢数字 with 万/億 grouping) -------------------
    // Japanese groups by 10,000 (万), not by 1,000 — so we use a separate algorithm.
    private static final String[] JA_DIGITS = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] JA_POW10 = {"", "十", "百", "千"};
    private static final String[] JA_MYRIAD = {"", "万", "億", "兆"};

    public static String convert(long number) {
        return convertVi(number);
    }

    public static String convert(long number, String languageCode) {
        if (languageCode == null) return convertVi(number);
        return switch (languageCode.toLowerCase()) {
            case "en" -> convertEn(number);
            case "ja" -> convertJa(number);
            default -> convertVi(number);
        };
    }

    // =========================================================================
    // Vietnamese
    // =========================================================================
    public static String convertVi(long number) {
        if (number == 0) return "Không đồng";
        if (number < 0) return "Âm " + convertVi(-number);
        String result = convertPositive(number);
        return capitalize(result.trim()) + " đồng";
    }

    private static String convertPositive(long n) {
        if (n == 0) return "";
        StringBuilder sb = new StringBuilder();
        int groupIdx = 0;
        while (n > 0) {
            int group = (int) (n % 1000);
            if (group != 0) {
                String groupText = convertBelow1000(group);
                String prefix = groupIdx > 0 ? groupText + " " + GROUPS[groupIdx] + " " : groupText;
                sb.insert(0, prefix + " ");
            }
            n /= 1000;
            groupIdx++;
        }
        return sb.toString().trim();
    }

    private static String convertBelow1000(int n) {
        if (n < 10) return UNITS[n];
        if (n < 100) return TENS[n / 10] + (n % 10 != 0 ? " " + UNITS[n % 10] : "");

        int hundreds = n / 100;
        int remainder = n % 100;
        String result = UNITS[hundreds] + " trăm";
        if (remainder > 0) {
            result += remainder < 10
                    ? " lẻ " + UNITS[remainder]
                    : " " + convertBelow1000(remainder);
        }
        return result;
    }

    // =========================================================================
    // English
    // =========================================================================
    public static String convertEn(long number) {
        if (number == 0) return "Zero Vietnamese dong";
        if (number < 0) return "Negative " + convertEn(-number);

        StringBuilder sb = new StringBuilder();
        int scale = 0;
        long n = number;
        while (n > 0) {
            int group = (int) (n % 1000);
            if (group != 0) {
                String text = convertEnBelow1000(group);
                if (scale > 0) text += " " + EN_SCALES[scale];
                sb.insert(0, text + " ");
            }
            n /= 1000;
            scale++;
        }
        return capitalize(sb.toString().trim()) + " Vietnamese dong";
    }

    private static String convertEnBelow1000(int n) {
        StringBuilder sb = new StringBuilder();
        if (n >= 100) {
            sb.append(EN_UNITS[n / 100]).append(" hundred");
            n %= 100;
            if (n > 0) sb.append(" ");
        }
        if (n >= 20) {
            sb.append(EN_TENS[n / 10]);
            if (n % 10 > 0) sb.append("-").append(EN_UNITS[n % 10]);
        } else if (n > 0) {
            sb.append(EN_UNITS[n]);
        }
        return sb.toString();
    }

    // =========================================================================
    // Japanese — legal style (金 prefix, 円 suffix, 整 terminator optional)
    // =========================================================================
    public static String convertJa(long number) {
        if (number == 0) return "零ベトナムドン";
        if (number < 0) return "マイナス" + convertJa(-number);

        StringBuilder sb = new StringBuilder();
        long n = number;
        int myriad = 0;
        while (n > 0) {
            int group = (int) (n % 10000);
            if (group != 0) {
                sb.insert(0, convertJaBelow10000(group) + JA_MYRIAD[myriad]);
            }
            n /= 10000;
            myriad++;
        }
        return sb + "ベトナムドン";
    }

    private static String convertJaBelow10000(int n) {
        StringBuilder sb = new StringBuilder();
        int[] digits = { n / 1000, (n / 100) % 10, (n / 10) % 10, n % 10 };
        for (int i = 0; i < 4; i++) {
            int d = digits[i];
            if (d == 0) continue;
            // 一 is omitted before 十/百/千 in legal Japanese except the leading digit of 千.
            if (d == 1 && i > 0 && i < 3) {
                sb.append(JA_POW10[3 - i]);
            } else {
                sb.append(JA_DIGITS[d]).append(JA_POW10[3 - i]);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
