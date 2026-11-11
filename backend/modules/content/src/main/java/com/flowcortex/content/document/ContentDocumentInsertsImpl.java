package com.flowcortex.content.document;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

class ContentDocumentInsertsImpl implements ContentDocumentInserts {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void persist(ContentDocument document) {
        entityManager.persist(document);
        entityManager.flush();
    }
}
