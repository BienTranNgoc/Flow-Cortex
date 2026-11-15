package com.flowcortex.mail.web;

import com.flowcortex.mail.links.LinkList;
import com.flowcortex.mail.links.LinkSource;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One {@code mail.link_action} line per route call. The address, the token and the client address never
 * appear; a field that is not known is logged as {@code -}.
 */
@Component
class LinkActionLog {

    enum Outcome { VIEWED, APPLIED, ALREADY, INVALID, RATE_LIMITED }

    private static final Logger LOG = LoggerFactory.getLogger(LinkActionLog.class);

    void record(Outcome outcome, LinkList list, LinkSource source, String srcKey, UUID memberId) {
        LOG.info("mail.link_action outcome={} list={} source={} src={} memberId={}",
                outcome.name().toLowerCase(Locale.ROOT), list == null ? "-" : list.wire(),
                source == null ? "-" : source.wire(), srcKey == null ? "-" : srcKey,
                memberId == null ? "-" : memberId);
    }
}
