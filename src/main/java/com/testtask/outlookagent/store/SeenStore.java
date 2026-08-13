package com.testtask.outlookagent.store;

public interface SeenStore {

    boolean isSeen(String messageId);

    void markSeen(String messageId);
}
