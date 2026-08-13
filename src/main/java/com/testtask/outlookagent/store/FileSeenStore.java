package com.testtask.outlookagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class FileSeenStore implements SeenStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path path;

    public FileSeenStore(Path path) {
        this.path = path;
    }

    @Override
    public boolean isSeen(String messageId) {
        return readState().seenIds.contains(messageId);
    }

    @Override
    public void markSeen(String messageId) {
        State state = readState();
        state.seenIds.add(messageId);
        state.pendingReplies.remove(messageId);
        writeState(state);
    }

    @Override
    public Optional<String> getPendingReply(String messageId) {
        return Optional.ofNullable(readState().pendingReplies.get(messageId));
    }

    @Override
    public void savePendingReply(String messageId, String replyBody) {
        State state = readState();
        state.pendingReplies.put(messageId, replyBody);
        writeState(state);
    }

    private State readState() {
        if (!Files.exists(path)) {
            return new State();
        }
        try {
            byte[] json = Files.readAllBytes(path);
            if (json.length == 0) {
                return new State();
            }
            return OBJECT_MAPPER.readValue(json, State.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeState(State state) {
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(state);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class State {
        public Set<String> seenIds = new LinkedHashSet<>();
        public Map<String, String> pendingReplies = new LinkedHashMap<>();
    }
}
