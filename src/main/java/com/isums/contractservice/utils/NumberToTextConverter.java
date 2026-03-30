package com.isums.contractservice.utils;

public class NumberToTextConverter {

    private static final String[] UNITS = {"", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};
    private static final String[] TENS = {"", "mười", "hai mươi", "ba mươi", "bốn mươi", "năm mươi", "sáu mươi", "bảy mươi", "tám mươi", "chín mươi"};
    private static final String[] GROUPS = {"", "nghìn", "triệu", "tỷ"};

    public static String convert(long number) {
        if (number == 0) return "Không đồng";
        if (number < 0) return "Âm " + convert(-number);

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

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}