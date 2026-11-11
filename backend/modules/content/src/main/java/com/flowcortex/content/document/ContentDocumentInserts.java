package com.flowcortex.content.document;

/** Writes for rows that carry an explicit first version, which {@code JpaRepository.save} would route to a merge. */
interface ContentDocumentInserts {

    /** Inserts the row now, so a unique-key violation surfaces at this call rather than at commit. */
    void persist(ContentDocument document);
}
