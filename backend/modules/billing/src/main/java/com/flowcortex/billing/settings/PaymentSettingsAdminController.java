package com.flowcortex.billing.settings;

import com.flowcortex.settings.api.SettingChangeEntry;
import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/admin/payment-settings} — the payment-operation keys of the settings registry, one
 * write per key and their change history. Any key outside the payment scope answers as unknown. The
 * {@code {key:.+}} variable keeps the dots of a key name.
 */
@RestController
@RequestMapping("/api/v1/admin/payment-settings")
class PaymentSettingsAdminController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSettingsAdminController.class);
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 2000;
    private static final String REASON_REQUIRED_CODE = "paymentSetting.reasonRequired";

    private final Settings settings;
    private final PaymentSettingCatalog catalog;
    private final UserDirectory users;

    PaymentSettingsAdminController(Settings settings, PaymentSettingCatalog catalog, UserDirectory users) {
        this.settings = settings;
        this.catalog = catalog;
        this.users = users;
    }

    /** A JSON string for an ENUM key, a boolean for a SWITCH key, an integer for an INTEGER key. */
    @Schema(name = "PaymentSettingUpdate")
    record PaymentSettingUpdate(
            @Schema(requiredMode = RequiredMode.REQUIRED, implementation = Object.class,
                    oneOf = {String.class, Boolean.class, Integer.class})
            @NotNull JsonNode value,
            @Schema(nullable = true, maxLength = MAX_REASON_LENGTH) @Size(max = MAX_REASON_LENGTH) String reason) {
    }

    /**
     * Values are JSON strings, booleans or integers by {@code type}. {@code value} is the one in force;
     * {@code storedValue} is null when nothing usable is stored, {@code configuredValue} when the server
     * configuration leaves it unset and {@code defaultValue} for an integer. {@code options} lists an enum's
     * options and is empty otherwise; {@code min} and {@code max} are null unless the key is an integer.
     */
    @Schema(name = "PaymentSettingView")
    record PaymentSettingView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String key,
            @Schema(requiredMode = RequiredMode.REQUIRED) String group,
            @Schema(requiredMode = RequiredMode.REQUIRED) SettingType type,
            @Schema(requiredMode = RequiredMode.REQUIRED, oneOf = {String.class, Boolean.class, Integer.class})
            Object value,
            @Schema(requiredMode = RequiredMode.REQUIRED) SettingSource source,
            @Schema(nullable = true, oneOf = {String.class, Boolean.class, Integer.class}) Object storedValue,
            @Schema(nullable = true, oneOf = {String.class, Boolean.class, Integer.class}) Object configuredValue,
            @Schema(nullable = true, oneOf = {String.class, Boolean.class, Integer.class}) Object defaultValue,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean dangerous,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> options,
            @Schema(nullable = true) Integer min,
            @Schema(nullable = true) Integer max) {
    }

    /**
     * Values are the stored text. {@code previousValue} is null for the first write; {@code actorEmail} is
     * null when the account is gone and {@code reason} when none was given.
     */
    @Schema(name = "PaymentSettingChangeView")
    record PaymentSettingChangeView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String key,
            @Schema(nullable = true) String previousValue,
            @Schema(nullable = true) String newValue,
            @Schema(nullable = true) UUID actorId,
            @Schema(nullable = true) String actorEmail,
            @Schema(nullable = true) String reason,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant changedAt) {
    }

    @Operation(operationId = "listPaymentSettings")
    @RequirePermission(Permission.PAYMENT_SETTING_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<PaymentSettingView> list() {
        return catalog.ordered(settings.describe(SettingScope.PAYMENT_OPS)).stream().map(this::toView).toList();
    }

    @Operation(operationId = "listPaymentSettingHistory")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.page, errors.size)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not-found (key is not a payment setting)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PAYMENT_SETTING_VIEW)
    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    PageView<PaymentSettingChangeView> history(@RequestParam(required = false) String key,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Optional<String> served = Optional.ofNullable(key);
        served.ifPresent(this::requireServed);
        PageView<SettingChangeEntry> entries = settings.history(SettingScope.PAYMENT_OPS, served, page, size);
        Set<UUID> actorIds = new LinkedHashSet<>();
        entries.items().forEach(entry -> {
            if (entry.actor() != null) {
                actorIds.add(entry.actor());
            }
        });
        Map<UUID, String> emails = actorIds.isEmpty() ? Map.of() : users.findEmailsByIds(actorIds);
        return entries.map(entry -> new PaymentSettingChangeView(entry.id(), entry.name(), entry.previousValue(),
                entry.newValue(), entry.actor(), entry.actor() == null ? null : emails.get(entry.actor()),
                entry.reason(), entry.changedAt()));
    }

    @Operation(operationId = "updatePaymentSetting")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.value, errors.reason)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "forbidden (needs payment.setting.update)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not-found (key is not a payment setting)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "paymentSetting.reasonRequired (errors.reason) or setting.invalidValue (errors.value)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PAYMENT_SETTING_UPDATE)
    @PutMapping(value = "/{key:.+}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PaymentSettingView update(Principal caller, @PathVariable String key,
            @Valid @RequestBody PaymentSettingUpdate body) {
        UUID actor = UUID.fromString(caller.getName());
        WriteVerdict verdict = PaymentSettingWritePolicy.decide(catalog.meta(key), body.reason());
        SettingDescription written = switch (verdict) {
            case WriteVerdict.Unknown notServed -> throw unknown();
            case WriteVerdict.ReasonRequired noReason -> throw new UnprocessableEntityException(
                    new LocalizedMessage("paymentSetting.reasonRequired"), REASON_REQUIRED_CODE,
                    Map.of("reason", new LocalizedMessage("paymentSetting.reasonRequired")));
            case WriteVerdict.Proceed proceed ->
                    settings.write(key, SettingScope.PAYMENT_OPS, body.value(), actor, proceed.reason());
        };
        LOG.info("payment setting written key={} actor={}", key, actor);
        return toView(written);
    }

    private void requireServed(String key) {
        if (catalog.meta(key).isEmpty()) {
            throw unknown();
        }
    }

    private static NotFoundException unknown() {
        return new NotFoundException(new LocalizedMessage("paymentSetting.unknown"));
    }

    private PaymentSettingView toView(SettingDescription description) {
        PaymentSettingMeta meta = catalog.meta(description.name()).orElseThrow();
        return new PaymentSettingView(description.name(), meta.group().value(), description.type(),
                description.effective(), description.source(), description.stored(), description.configured(),
                description.codeDefault(), meta.dangerous(), description.options(), description.min(),
                description.max());
    }
}
