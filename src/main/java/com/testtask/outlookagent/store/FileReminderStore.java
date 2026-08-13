package com.testtask.outlookagent.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FileReminderStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Reminder>> REMINDER_LIST_TYPE = new TypeReference<List<Reminder>>() {
    };

    private final Path path;

    public FileReminderStore(Path path) {
        this.path = path;
    }

    public void add(Reminder reminder) {
        List<Reminder> reminders = readAll();
        reminders.add(reminder);
        writeAll(reminders);
    }

    public List<Reminder> find(String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<Reminder> matches = new ArrayList<>();
        for (Reminder reminder : readAll()) {
            if (reminder.getText().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                matches.add(reminder);
            }
        }
        return matches;
    }

    private List<Reminder> readAll() {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            byte[] json = Files.readAllBytes(path);
            if (json.length == 0) {
                return new ArrayList<>();
            }
            return OBJECT_MAPPER.readValue(json, REMINDER_LIST_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeAll(List<Reminder> reminders) {
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(reminders);
            Files.write(path, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
