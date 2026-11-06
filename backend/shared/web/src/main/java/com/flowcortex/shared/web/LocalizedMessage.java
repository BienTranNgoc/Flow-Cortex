package com.flowcortex.shared.web;

import java.util.Arrays;
import org.springframework.context.MessageSourceResolvable;

/**
 * A message bundle key plus its {@code {0}}-style arguments, resolved into the request's language
 * by {@link GlobalExceptionHandler}. {@link #toString()} is the code and arguments, for logs.
 */
public record LocalizedMessage(String code, Object... args) implements MessageSourceResolvable {

    public LocalizedMessage {
        args = args == null ? new Object[0] : args;
    }

    @Override
    public String[] getCodes() {
        return new String[] {code};
    }

    @Override
    public Object[] getArguments() {
        return args;
    }

    @Override
    public String getDefaultMessage() {
        return null;
    }

    @Override
    public String toString() {
        return args.length == 0 ? code : code + " " + Arrays.toString(args);
    }
}
