package com.flowcortex.chat.tools;

import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmChatListener;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmChatRequest;
import com.flowcortex.llm.api.LlmTurn;
import java.util.ArrayList;
import java.util.List;

/**
 * The tool loop of one turn: ask the model, run the calls it made, replay them as turns and ask again. At most
 * {@code maxRounds} rounds of calls are run; when the model asks for more after that, the turn ends with the text
 * streamed so far instead of another request.
 */
public final class ToolRounds {

    private ToolRounds() {
    }

    /**
     * @param outcome the round that ended the turn; a {@code Finished} carries the tokens of every round and no
     *        pending calls
     * @param limitReached the model still asked for tools after the last allowed round
     * @param cardProposed some call of the turn put a confirm card in the thread
     */
    public record Result(LlmChatOutcome outcome, boolean limitReached, boolean cardProposed) {
    }

    /** {@code onRound} runs after each round of tool calls, before the next request. */
    public static Result run(LlmChatClient llm, LlmChatRequest first, ChatTools tools, ToolContext context,
            LlmChatListener listener, int maxRounds, Runnable onRound) {
        List<LlmTurn> turns = new ArrayList<>(first.turns());
        LlmChatRequest request = first;
        int rounds = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        boolean cardProposed = false;
        while (true) {
            LlmChatOutcome outcome = llm.stream(request, listener);
            if (!(outcome instanceof LlmChatOutcome.Finished finished)) {
                return new Result(outcome, false, cardProposed);
            }
            inputTokens += finished.inputTokens();
            outputTokens += finished.outputTokens();
            LlmChatOutcome.Finished total = new LlmChatOutcome.Finished(finished.model(), inputTokens,
                    outputTokens, List.of());
            if (finished.toolCalls().isEmpty()) {
                return new Result(total, false, cardProposed);
            }
            if (rounds == maxRounds) {
                return new Result(total, true, cardProposed);
            }
            if (listener.cancelled()) {
                return new Result(new LlmChatOutcome.Cancelled(), false, cardProposed);
            }
            rounds++;
            for (LlmChatOutcome.ToolCall call : finished.toolCalls()) {
                ToolResult result = tools.call(context, call.name(), call.argsJson());
                cardProposed |= result.cardProposed();
                turns.add(new LlmTurn.FunctionCall(call.name(), call.argsJson(), call.providerState()));
                turns.add(new LlmTurn.FunctionResult(call.name(), result.json()));
            }
            onRound.run();
            request = new LlmChatRequest(first.model(), first.systemInstruction(), turns, first.tools(),
                    first.temperature(), first.maxOutputTokens(), first.language(), first.thinkingBudget());
        }
    }
}
