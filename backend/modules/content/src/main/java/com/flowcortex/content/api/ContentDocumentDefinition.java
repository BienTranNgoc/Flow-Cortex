package com.flowcortex.content.api;

import java.util.List;

/**
 * A document registered in code: consumer modules declare one as a bean.
 *
 * @param slug          lowercase letters, digits and hyphens, at most 64 characters
 * @param title         the document's name in both languages
 * @param sectionTypes  the {@link SectionType#type()} values a section of this document may use
 * @param publicHistory whether anonymous readers may list and read earlier publications
 */
public record ContentDocumentDefinition(
        String slug, LocalizedText title, List<String> sectionTypes, boolean publicHistory) {
}
