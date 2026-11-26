package com.flowcortex.billing.ledger;

import com.flowcortex.billing.provider.PaymentAmountLimits;
import com.flowcortex.billing.provider.PaymentInstrument;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What it takes to open a payment. Every value is checked when the record is built, so an invalid opening
 * cannot exist and nothing reaches the database. The amount is held to the provider's charge range because a
 * pending payment the provider would refuse to charge is one nothing ever closes.
 *
 * @param kind {@code plan_purchase} or {@code topup}
 * @param itemRef the caller's reference to what is bought; for a plan purchase {@code <tier>:<cycle>}
 * @param description the text the member sees and the provider receives
 * @param instrument how the payer pays, when already chosen; an absent value reads as none
 */
public record OpenPayment(
        UUID userId,
        String kind,
        String itemRef,
        String description,
        long amountVnd,
        String provider,
        String merchantCode,
        Optional<PaymentInstrument> instrument) {

    private static final Set<String> KINDS = Set.of("plan_purchase", "topup");
    private static final Pattern ITEM_REF = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");
    private static final Pattern PROVIDER = Pattern.compile("[a-z][a-z0-9_]{1,31}");
    private static final int DESCRIPTION_MAX = 200;
    private static final int MERCHANT_CODE_MAX = 64;

    public OpenPayment {
        require(userId != null, "userId");
        require(kind != null && KINDS.contains(kind), "kind");
        require(itemRef != null && ITEM_REF.matcher(itemRef).matches(), "itemRef");
        require(!"plan_purchase".equals(kind) || PlanItemRef.parse(itemRef).isPresent(), "itemRef");
        require(isAcceptableDescription(description), "description");
        require(amountVnd > 0 && !PaymentAmountLimits.outsideProviderRange(amountVnd), "amountVnd");
        require(provider != null && PROVIDER.matcher(provider).matches(), "provider");
        require(merchantCode != null
                        && !merchantCode.isBlank()
                        && merchantCode.codePointCount(0, merchantCode.length()) <= MERCHANT_CODE_MAX,
                "merchantCode");
        instrument = instrument == null ? Optional.empty() : instrument;
    }

    private static boolean isAcceptableDescription(String description) {
        return description != null
                && !description.isBlank()
                && description.codePointCount(0, description.length()) <= DESCRIPTION_MAX
                && description.codePoints().noneMatch(Character::isISOControl);
    }

    private static void require(boolean accepted, String field) {
        if (!accepted) {
            throw new PaymentOpeningRefusedException(field);
        }
    }
}
