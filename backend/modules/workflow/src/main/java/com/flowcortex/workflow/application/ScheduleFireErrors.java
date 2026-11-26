package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedProblem;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes a start-path refusal as what a fire stores: a machine code, and the message as a bundle key
 * with its arguments. The text is never taken from {@code getMessage()}, which is only the key for logs, and
 * is resolved when the history is read, in the reader's language.
 */
final class ScheduleFireErrors {

    static final String START_ERROR_CODE = "startError";
    static final String START_FAILED_KEY = "workflow.schedule.startFailed";

    private ScheduleFireErrors() {}

    /** {@code args} holds only strings and numbers, so they survive a round trip through JSON. */
    record Described(String code, String messageKey, List<Object> args) {}

    /**
     * The code is the one the refusal's HTTP answer would carry, else its message key; a failure that
     * carries no localized message is a {@code startError} with a generic key, so nothing internal is stored.
     */
    static Described describe(Throwable failure) {
        if (failure instanceof InsufficientCreditsException e) {
            return described(InsufficientCreditsException.CODE, e.getDetail());
        }
        if (failure instanceof UnprocessableEntityException e) {
            return described(e.getCode() == null ? e.getDetail().code() : e.getCode(), e.getDetail());
        }
        if (failure instanceof LocalizedStatusException e) {
            return new Described(problemCode(e), e.getDetailMessageCode(), arguments(e.getDetailMessageArguments()));
        }
        if (failure instanceof UploadRejectedException e) {
            return described(e.detail().code(), e.detail());
        }
        if (failure instanceof LocalizedProblem e) {
            return described(e.localizedDetail().code(), e.localizedDetail());
        }
        return new Described(START_ERROR_CODE, START_FAILED_KEY, List.of());
    }

    private static Described described(String code, LocalizedMessage detail) {
        return new Described(code, detail.code(), arguments(detail.args()));
    }

    private static String problemCode(LocalizedStatusException e) {
        Object code = e.getBody().getProperties() == null ? null : e.getBody().getProperties().get("code");
        return code instanceof String text ? text : e.getDetailMessageCode();
    }

    private static List<Object> arguments(Object[] args) {
        List<Object> kept = new ArrayList<>(args.length);
        for (Object arg : args) {
            kept.add(arg instanceof String || arg instanceof Number ? arg : String.valueOf(arg));
        }
        return List.copyOf(kept);
    }
}
