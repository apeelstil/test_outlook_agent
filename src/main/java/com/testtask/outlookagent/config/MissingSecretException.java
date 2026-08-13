package com.testtask.outlookagent.config;

public class MissingSecretException extends RuntimeException {

    public MissingSecretException(String variableName) {
        super("Missing or blank required environment variable: " + variableName);
    }
}
