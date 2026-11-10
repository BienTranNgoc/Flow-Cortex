package com.flowcortex.support.request;

/** Everything that can change a request's status, by actor. */
enum RequestEvent {
    MEMBER_REPLY,
    MEMBER_CANCEL,
    MEMBER_CONFIRM,
    ADMIN_REPLY_WAITING_MEMBER,
    ADMIN_REPLY_RESOLVED,
    ADMIN_SET_IN_PROGRESS,
    ADMIN_SET_RESOLVED,
    ADMIN_SET_CLOSED,
    ADMIN_ASSIGN,
    AUTO_CLOSE
}
