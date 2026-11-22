package com.flowcortex.onboarding.domain;

import java.util.Collection;
import java.util.List;

/** Which locales carry a translation of the sample input and which input a request language is served. */
public final class SampleTranslations {

    /** The languages the product ships; the source language is not one of the editable ones. */
    private static final List<String> LOCALES = List.of("vi", "en");

    private SampleTranslations() {
    }

    /** A stored translation of the input, valid only for the sample whose hash it carries. */
    public record Translation(String sourceHash, String locale, String input) {
    }

    public static List<String> editableLocales(String sourceLocale) {
        return LOCALES.stream().filter(locale -> !locale.equals(sourceLocale)).toList();
    }

    /** The translation for the request language under the sample's current hash, else the source input. */
    public static String servedInput(StoredSample sample, Collection<Translation> translations,
            String requestLanguage) {
        String hash = sample.sourceHash();
        return translations.stream()
                .filter(t -> t.sourceHash().equals(hash) && t.locale().equals(requestLanguage))
                .map(Translation::input)
                .findFirst()
                .orElse(sample.sampleInput());
    }
}
