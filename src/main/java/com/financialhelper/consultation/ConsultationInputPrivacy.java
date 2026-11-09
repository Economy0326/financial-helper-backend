package com.financialhelper.consultation;

import java.util.regex.Pattern;

/** Conservative, local detection of clear identifiers before persistence or provider use. */
public final class ConsultationInputPrivacy {
    private static final Pattern RESIDENT_ID = Pattern.compile("(?<!\\d)\\d{6}[\\s-]*[1-8](?:[\\s-]*\\d){6}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![\\w.])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w.-])");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)01[016789][\\s.-]*\\d{3,4}[\\s.-]*\\d{4}(?!\\d)");
    private static final Pattern LANDLINE = Pattern.compile("(?<!\\d)(?:02|0[3-6][1-5])[\\s.-]*\\d{3,4}[\\s.-]*\\d{4}(?!\\d)");
    private static final Pattern API_SECRET = Pattern.compile("(?i)\\b(?:bearer|api[_ -]?key|access[_ -]?token|secret[_ -]?key)\\s*[:=]?\\s*[A-Za-z0-9_./+=-]{8,}");
    private static final Pattern AUTH_SECRET = Pattern.compile("(?i)(?:인증번호|일회용\\s*비밀번호|비밀번호|패스워드|password|otp|pin)\\s*(?:은|는|이|가|:|=|\\s){0,5}\\s*[A-Za-z0-9!@#$%^&*_-]{4,}");
    private static final Pattern ACCOUNT = Pattern.compile("(?:계좌번호|입금계좌|출금계좌)\\s*(?:는|은|이|가|:|=|\\s){0,5}\\s*(?<!\\d)(?:\\d[\\s-]?){8,16}(?!\\d)");
    private static final Pattern ADDRESS = Pattern.compile("(?:주소|거주지|집주소)\\s*(?:는|은|이|가|:|=|\\s){0,5}\\s*[^\\n,.]{2,35}(?:로|길|동)\\s*\\d{1,4}(?:-\\d{1,4})?");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");

    private ConsultationInputPrivacy() {}

    public static void requireSafe(String value) {
        if (value == null || value.isBlank()) return;
        if (RESIDENT_ID.matcher(value).find() || EMAIL.matcher(value).find()
                || MOBILE.matcher(value).find() || LANDLINE.matcher(value).find()
                || API_SECRET.matcher(value).find()
                || AUTH_SECRET.matcher(value).find() || ACCOUNT.matcher(value).find()
                || ADDRESS.matcher(value).find() || containsCardNumber(value)) {
            throw new UnsafeConsultationInputException();
        }
    }

    private static boolean containsCardNumber(String value) {
        var matches = CARD_CANDIDATE.matcher(value);
        while (matches.find()) {
            String digits = matches.group().replaceAll("[ -]", "");
            if (digits.length() < 13 || digits.length() > 19) continue;
            int sum = 0;
            boolean doubled = false;
            for (int index = digits.length() - 1; index >= 0; index--) {
                int number = digits.charAt(index) - '0';
                if (doubled && (number *= 2) > 9) number -= 9;
                sum += number;
                doubled = !doubled;
            }
            if (sum % 10 == 0) return true;
        }
        return false;
    }
}
