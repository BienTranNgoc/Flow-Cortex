package com.flowcortex.content.api;

import java.util.List;

/**
 * A document registered in code: consumer modules declare one as a bean.
 *
 * @param slug          lowercase letters, digits and hyphens, at most 64 characters
 * @param title         the document's name in both languages
 * @param sectionTypes  the {@link SectionType#type()} values a section of this document may use
 * @param publicHistory whether anonymous readers may list and read earlier publications
 * @param minSections   sections a publication needs, 0 to 100 and at most {@code maxSections}
 * @param maxSections   sections a draft may hold, 0 to 100
 */
public record ContentDocumentDefinition(
        String slug, LocalizedText title, List<String> sectionTypes, boolean publicHistory,
        int minSections, int maxSections) {

    /** The engine-wide ceiling on sections; the default {@code maxSections}. */
    public static final int SECTION_CEILING = 100;

    /** A document with no section count rule: zero to {@link #SECTION_CEILING} sections. */
    public ContentDocumentDefinition(
            String slug, LocalizedText title, List<String> sectionTypes, boolean publicHistory) {
        this(slug, title, sectionTypes, publicHistory, 0, SECTION_CEILING);
    }
}
