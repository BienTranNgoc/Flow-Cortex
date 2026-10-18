package com.flowcortex.user.phone;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Vietnamese mobile numbers: {@code 0} or {@code +84}, a 3/5/7/8/9 prefix, eight more digits. */
final class PhoneNumbers {

    static final String VN_MOBILE = "^(0|\\+84)([35789]\\d{8})$";
    private static final Pattern VN_MOBILE_PATTERN = Pattern.compile(VN_MOBILE);

    private PhoneNumbers() {
    }

    /** {@code 0912345678} or {@code +84912345678} → {@code +84912345678}; anything else is empty. */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = VN_MOBILE_PATTERN.matcher(raw);
        return matcher.matches() ? Optional.of("+84" + matcher.group(2)) : Optional.empty();
    }

    /** {@code +84912345678} → {@code +84 9•• ••• 678}, what the member sees. */
    static String mask(String e164) {
        return "+84 " + e164.charAt(3) + "•• ••• " + lastThree(e164);
    }

    /** {@code +84912345678} → {@code ***678}: the only form of a number a log line may hold. */
    static String forLog(String e164) {
        return "***" + lastThree(e164);
    }

    private static String lastThree(String e164) {
        return e164.substring(e164.length() - 3);
    }
}
