package com.flowcortex.asset.application;

import java.util.concurrent.RejectedExecutionException;

/**
 * The threads that register assets, apart from the shared async pool so run advancement never
 * waits behind object-store calls.
 */
public interface AssetRegistrationPool {

    /** @throws RejectedExecutionException when every thread is busy and the queue is full */
    void execute(Runnable task);
}
