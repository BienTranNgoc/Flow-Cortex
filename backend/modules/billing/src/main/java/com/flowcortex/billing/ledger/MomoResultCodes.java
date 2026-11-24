package com.flowcortex.billing.ledger;

import com.flowcortex.billing.ledger.ProviderCodeTables.CodeRule;
import java.util.Map;
import java.util.Optional;

/**
 * What each of MoMo's result codes means for a payment's money, keyed by the code as it arrives. {@code 9000}
 * is authorised, not captured, so it is {@code pending}; {@code 1000}, {@code 7000} and {@code 7002} mean
 * the payment is still in flight. A code with no money meaning derives no status, which is not a failure.
 */
final class MomoResultCodes {

    private static final String NONE = "none";
    private static final String CUSTOMER = "customer";
    private static final String MERCHANT = "merchant";
    private static final String PROVIDER = "provider";

    private static final Map<String, CodeRule> TABLE = Map.ofEntries(
            paid("0", NONE),
            pending("1000", NONE),
            pending("7000", NONE),
            pending("7002", NONE),
            pending("9000", NONE),
            failed("1001", CUSTOMER),
            failed("1002", CUSTOMER),
            failed("1003", MERCHANT),
            failed("1004", CUSTOMER),
            failed("1006", CUSTOMER),
            failed("1007", CUSTOMER),
            failed("1017", MERCHANT),
            failed("1026", CUSTOMER),
            failed("1080", MERCHANT),
            failed("1081", MERCHANT),
            failed("1088", MERCHANT),
            failed("2001", CUSTOMER),
            failed("2007", CUSTOMER),
            failed("2012", CUSTOMER),
            failed("2019", MERCHANT),
            failed("4001", CUSTOMER),
            failed("4002", CUSTOMER),
            failed("4010", CUSTOMER),
            failed("4100", CUSTOMER),
            expired("1005", PROVIDER),
            unrelated("10", PROVIDER),
            unrelated("11007", MERCHANT),
            unrelated("13", MERCHANT),
            unrelated("20", MERCHANT),
            unrelated("2015", MERCHANT),
            unrelated("2016", CUSTOMER),
            unrelated("40", MERCHANT),
            unrelated("41", MERCHANT),
            unrelated("98", PROVIDER),
            unrelated("99", PROVIDER));

    private MomoResultCodes() {
    }

    static Map<String, CodeRule> table() {
        return TABLE;
    }

    private static Map.Entry<String, CodeRule> paid(String code, String owner) {
        return Map.entry(code, new CodeRule(Optional.of(MoneyStatus.SUCCEEDED), owner));
    }

    private static Map.Entry<String, CodeRule> pending(String code, String owner) {
        return Map.entry(code, new CodeRule(Optional.of(MoneyStatus.PENDING), owner));
    }

    private static Map.Entry<String, CodeRule> failed(String code, String owner) {
        return Map.entry(code, new CodeRule(Optional.of(MoneyStatus.FAILED), owner));
    }

    private static Map.Entry<String, CodeRule> expired(String code, String owner) {
        return Map.entry(code, new CodeRule(Optional.of(MoneyStatus.EXPIRED), owner));
    }

    private static Map.Entry<String, CodeRule> unrelated(String code, String owner) {
        return Map.entry(code, new CodeRule(Optional.empty(), owner));
    }
}
