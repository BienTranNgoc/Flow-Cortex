package com.flowcortex.support.assistant;

import com.flowcortex.support.kb.KnowledgeBaseService;
import com.flowcortex.support.kb.KnowledgeBaseService.ScoredArticle;
import java.util.List;
import org.springframework.stereotype.Component;

/** The published articles a question is answered from: the knowledge base's own search, best six. */
@Component
class Retriever {

    private final KnowledgeBaseService knowledgeBase;

    Retriever(KnowledgeBaseService knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    List<ScoredArticle> retrieve(String question) {
        return knowledgeBase.search(question, PromptBuilder.MAX_ARTICLES);
    }
}
