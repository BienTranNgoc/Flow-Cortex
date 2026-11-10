package com.flowcortex.user.audit;

import com.flowcortex.shared.web.audit.AdminAudit;
import java.util.Map;
import java.util.UUID;

/** What the recording path collects about one admin request until it completes. */
final class PendingAudit {

    static final String ATTRIBUTE = PendingAudit.class.getName();

    private final AdminAudit annotation;
    private final UUID actorUserId;
    private final ActorSnapshot actor;
    private final String ip;
    private final String userAgent;
    private final Map<String, String> pathVariables;
    private Object requestBody;
    private Object responseBody;
    private Throwable exception;

    PendingAudit(AdminAudit annotation, UUID actorUserId, ActorSnapshot actor, String ip, String userAgent,
            Map<String, String> pathVariables) {
        this.annotation = annotation;
        this.actorUserId = actorUserId;
        this.actor = actor;
        this.ip = ip;
        this.userAgent = userAgent;
        this.pathVariables = pathVariables;
    }

    AdminAudit annotation() {
        return annotation;
    }

    UUID actorUserId() {
        return actorUserId;
    }

    ActorSnapshot actor() {
        return actor;
    }

    String ip() {
        return ip;
    }

    String userAgent() {
        return userAgent;
    }

    Map<String, String> pathVariables() {
        return pathVariables;
    }

    Object requestBody() {
        return requestBody;
    }

    void requestBody(Object body) {
        this.requestBody = body;
    }

    Object responseBody() {
        return responseBody;
    }

    void responseBody(Object body) {
        this.responseBody = body;
    }

    Throwable exception() {
        return exception;
    }

    void exception(Throwable exception) {
        this.exception = exception;
    }
}
