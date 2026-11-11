package com.flowcortex.onboarding.domain;

/** Decides what saving a translation does; the checks run in the order of {@link Outcome}. */
public final class TranslationEdit {

    public enum Outcome {
        NOT_SET, STALE, LOCALE_NOT_EDITABLE, CLEAR, SAVE
    }

    private TranslationEdit() {
    }

    /** {@code stored} is null when no sample is configured; a blank {@code input} removes the translation. */
    public static Outcome check(StoredSample stored, String locale, String sourceHash, String input) {
        if (stored == null) {
            return Outcome.NOT_SET;
        }
        if (!stored.sourceHash().equals(sourceHash)) {
            return Outcome.STALE;
        }
        if (!SampleTranslations.editableLocales(stored.sourceLocale()).contains(locale)) {
            return Outcome.LOCALE_NOT_EDITABLE;
        }
        return input.isBlank() ? Outcome.CLEAR : Outcome.SAVE;
    }
}
