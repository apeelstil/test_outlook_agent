package com.testtask.outlookagent.store;

import java.util.Optional;

public interface SeenStore {

    boolean isSeen(String messageId);

    void markSeen(String messageId);

    /**
     * A reply already produced by the Agent for this (not-yet-seen) message
     * id, durably saved by a prior processing attempt whose reply delivery
     * failed. Absent if no such reply is pending.
     */
    default Optional<String> getPendingReply(String messageId) {
        return Optional.empty();
    }

    /**
     * Durably records the reply body for a message id before delivery is
     * attempted, so a failed delivery can be retried without re-running the
     * Agent (and any side-effecting tools) for that id.
     */
    default void savePendingReply(String messageId, String replyBody) {
    }
}
