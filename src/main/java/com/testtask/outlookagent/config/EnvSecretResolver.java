package com.testtask.outlookagent.config;

import java.util.Map;

public class EnvSecretResolver {

    private final Map<String, String> env;

    public EnvSecretResolver(Map<String, String> env) {
        this.env = env;
    }

    public String resolve(String variableName) {
        String value = env.get(variableName);
        if (value == null || value.trim().isEmpty()) {
            throw new MissingSecretException(variableName);
        }
        return value;
    }
}
