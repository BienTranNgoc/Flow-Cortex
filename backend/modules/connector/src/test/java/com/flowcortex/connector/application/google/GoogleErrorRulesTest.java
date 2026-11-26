package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.application.google.GoogleErrorRules.Api;
import com.flowcortex.connector.application.google.GoogleErrorRules.Item;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GoogleErrorRulesTest {

    private static final String GOOGLE_TEXT = "secret ya29.x";

    private static ConnectorException classify(Api api, Item item, int status, String reason, String domain,
            String message) {
        ConnectorException ex = GoogleErrorRules.classify(api, item, status, reason, domain, message,
                Duration.ofSeconds(30));
        assertThat(ex.getMessage()).doesNotContain("ya29");
        assertThat(ex.toString()).doesNotContain("ya29");
        assertThat(ex.detail().args()).noneMatch(arg -> String.valueOf(arg).contains("ya29"));
        return ex;
    }

    private static void assertRefused(ConnectorException ex, String key, String providerReason) {
        assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
        assertThat(ex.retryable()).isFalse();
        assertThat(ex.detail().code()).isEqualTo(key);
        assertThat(ex.providerReason()).isEqualTo(providerReason);
    }

    private static void assertRateLimited(ConnectorException ex) {
        assertThat(ex.reason()).isEqualTo(Reason.RATE_LIMITED);
        assertThat(ex.retryable()).isTrue();
        assertThat(ex.detail().code()).isEqualTo("connector.google.rateLimited");
        assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(ex.providerReason()).isNull();
    }

    @Test
    void a401AsksToReconnect() {
        assertRefused(classify(Api.DRIVE, Item.OTHER, 401, null, null, GOOGLE_TEXT), "connector.google.reconnect",
                "reconnect");
    }

    @Test
    void aMissingPermissionIsAScopeFault() {
        assertRefused(classify(Api.DRIVE, Item.OTHER, 403, "insufficientPermissions", null, GOOGLE_TEXT),
                "connector.google.scopeMissing", "scope_missing");
        assertRefused(classify(Api.SHEETS, Item.OTHER, 403, "ACCESS_TOKEN_SCOPE_INSUFFICIENT", null, GOOGLE_TEXT),
                "connector.google.scopeMissing", "scope_missing");
    }

    @Test
    void everyQuotaSignalIsRateLimitedAndKeepsGooglesWait() {
        assertRateLimited(classify(Api.SHEETS, Item.OTHER, 429, null, null, GOOGLE_TEXT));
        for (String reason : new String[] {"userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded",
                "sharingRateLimitExceeded"}) {
            assertRateLimited(classify(Api.DRIVE, Item.OTHER, 403, reason, "usageLimits", GOOGLE_TEXT));
        }
        assertRateLimited(classify(Api.DRIVE, Item.OTHER, 403, "other", "usageLimits", GOOGLE_TEXT));
    }

    @Test
    void aMissingOrHiddenItemIsNotFoundAndAMissingOrHiddenFolderNamesTheFolder() {
        assertRefused(classify(Api.DRIVE, Item.OTHER, 404, null, null, GOOGLE_TEXT), "connector.google.notFound",
                "not_found");
        assertRefused(classify(Api.DRIVE, Item.OTHER, 403, "forbidden", "global", GOOGLE_TEXT),
                "connector.google.notFound", "not_found");
        assertRefused(classify(Api.DRIVE, Item.FOLDER, 404, null, null, GOOGLE_TEXT),
                "connector.google.folderNoAccess", "folder_no_access");
        assertRefused(classify(Api.DRIVE, Item.FOLDER, 403, "forbidden", "global", GOOGLE_TEXT),
                "connector.google.folderNoAccess", "folder_no_access");
    }

    @Test
    void aSheetsRefusalOfAnExcelFileIsNotNativeSheetButTheSame400OnDriveIsRejected() {
        String text = "This operation is not supported for this document";

        assertRefused(classify(Api.SHEETS, Item.OTHER, 400, "failedPrecondition", null, text),
                "connector.google.notNativeSheet", "not_native_sheet");
        assertRefused(classify(Api.DRIVE, Item.OTHER, 400, "failedPrecondition", null, text),
                "connector.google.rejected", "rejected");
    }

    @Test
    void anyOtherClientErrorIsRejected() {
        assertRefused(classify(Api.SHEETS, Item.OTHER, 400, "badRequest", null, GOOGLE_TEXT),
                "connector.google.rejected", "rejected");
        assertRefused(classify(Api.SHEETS, Item.OTHER, 409, null, null, GOOGLE_TEXT), "connector.google.rejected",
                "rejected");
    }

    @Test
    void aServerErrorIsUpstreamUnavailableWithNoProviderReason() {
        for (int status : new int[] {500, 503}) {
            ConnectorException ex = classify(Api.DRIVE, Item.OTHER, status, null, null, GOOGLE_TEXT);

            assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE);
            assertThat(ex.providerReason()).isNull();
        }
    }

    @Test
    void anUnparseableAnswerIsUpstreamUnavailableAndNotRetryable() {
        ConnectorException ex = GoogleErrorRules.unparseable();

        assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE);
        assertThat(ex.retryable()).isFalse();
        assertThat(ex.providerReason()).isNull();
    }
}
