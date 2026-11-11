package com.flowcortex.workflow.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A partial change to a run. An absent {@code title} or {@code favorite} stays as it is; a {@code title}
 * sent as {@code null} or empty clears it, so the request records whether {@code title} was sent at all.
 */
final class UpdateRunRequest {

    @Schema(type = "string", nullable = true, maxLength = 120)
    private String title;
    @Schema(type = "boolean")
    private Boolean favorite;
    private boolean titleSent;

    @JsonSetter("title")
    void setTitle(String title) {
        this.title = title;
        this.titleSent = true;
    }

    @JsonSetter("favorite")
    void setFavorite(Boolean favorite) {
        this.favorite = favorite;
    }

    String title() {
        return title;
    }

    boolean titleSent() {
        return titleSent;
    }

    Boolean favorite() {
        return favorite;
    }
}
