package com.flowcortex.onboarding.application;

import com.flowcortex.shared.web.LocalizedMessage;

/** Turns the bundle keys the parsers record into messages; each code is a literal so the bundle parity test sees it. */
final class OnboardingMessages {

    private OnboardingMessages() {
    }

    static LocalizedMessage of(String key) {
        return switch (key) {
            case "onboarding.field.unknownValue" -> new LocalizedMessage("onboarding.field.unknownValue");
            case "onboarding.field.wrongType" -> new LocalizedMessage("onboarding.field.wrongType");
            case "onboarding.field.emptyList" -> new LocalizedMessage("onboarding.field.emptyList");
            case "onboarding.field.duplicate" -> new LocalizedMessage("onboarding.field.duplicate");
            case "onboarding.field.tooMany" -> new LocalizedMessage("onboarding.field.tooMany");
            case "onboarding.field.notNull" -> new LocalizedMessage("onboarding.field.notNull");
            case "onboarding.field.outOfRange" -> new LocalizedMessage("onboarding.field.outOfRange");
            case "onboarding.builder.state.notObject" -> new LocalizedMessage("onboarding.builder.state.notObject");
            case "onboarding.builder.state.tooLarge" -> new LocalizedMessage("onboarding.builder.state.tooLarge");
            case "onboarding.builder.reason.unknown" -> new LocalizedMessage("onboarding.builder.reason.unknown");
            default -> throw new IllegalStateException("No message for onboarding key " + key);
        };
    }
}
