package com.flowcortex.llm.api;

/** One entry of the conversation sent to the model. */
public sealed interface LlmTurn
        permits LlmTurn.User, LlmTurn.Model, LlmTurn.FunctionCall, LlmTurn.FunctionResult {

    record User(String text) implements LlmTurn {
    }

    record Model(String text) implements LlmTurn {
    }

    /** A call the model made earlier; {@code providerState} is what the listener received with it. */
    record FunctionCall(String name, String argsJson, String providerState) implements LlmTurn {
    }

    record FunctionResult(String name, String resultJson) implements LlmTurn {
    }
}
