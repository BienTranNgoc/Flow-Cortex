package com.flowcortex.shared.web;

/** An exception whose refusal is carried by a message bundle key, readable without its arguments. */
public interface LocalizedProblem {

    LocalizedMessage localizedDetail();
}
