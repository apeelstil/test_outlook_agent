package com.testtask.outlookagent.store;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Reminder {

    private final String text;
    private final String dueIso;

    @JsonCreator
    public Reminder(@JsonProperty("text") String text, @JsonProperty("dueIso") String dueIso) {
        this.text = text;
        this.dueIso = dueIso;
    }

    public String getText() {
        return text;
    }

    public String getDueIso() {
        return dueIso;
    }
}
