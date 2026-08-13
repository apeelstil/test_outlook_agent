package com.testtask.outlookagent.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public class FileSeenStore implements SeenStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Set<String>> SEEN_IDS_TYPE = new TypeReference<Set<String>>() {
    };

    private final Path path;

    public FileSeenStore(Path path) {
        this.path = path;
    }

    @Override
    public boolean isSeen(String messageId) {
        return readAll().contains(messageId);
    }

    @Override
    public void markSeen(String messageId) {
        Set<String> seenIds = readAll();
        seenIds.add(messageId);
        writeAll(seenIds);
    }

    private Set<String> readAll() {
        if (!Files.exists(path)) {
            return new LinkedHashSet<>();
        }
        try {
            byte[] json = Files.readAllBytes(path);
            if (json.length == 0) {
                return new LinkedHashSet<>();
            }
            return OBJECT_MAPPER.readValue(json, SEEN_IDS_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeAll(Set<String> seenIds) {
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(seenIds);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
