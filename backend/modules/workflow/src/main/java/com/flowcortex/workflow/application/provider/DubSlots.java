package com.flowcortex.workflow.application.provider;

/** The llm slots the dub steps call; each must be configured under {@code flowcortex.llm.gemini.slots}. */
public final class DubSlots {

    public static final String TRANSLATE = "dub-translate";
    public static final String SPEECH = "dub-speech";

    private DubSlots() {
    }
}
