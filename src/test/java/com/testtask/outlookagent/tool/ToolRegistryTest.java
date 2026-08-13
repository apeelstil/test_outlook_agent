package com.testtask.outlookagent.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

/**
 * RED spec (Roadmap #4): describes the expected Tool/ToolRegistry contract
 * before any production code exists. See PLAN.md TDD roadmap.
 */
public class ToolRegistryTest {

    /** Minimal fake tool used only to drive this RED spec. */
    private static class EchoTool implements Tool {

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return args.get("value");
        }
    }

    @Test
    public void registersAndFindsToolByName() {
        EchoTool echoTool = new EchoTool();

        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool);

        Optional<Tool> found = registry.findByName("echo");

        assertTrue(found.isPresent());
        assertEquals("echo", found.get().getName());

        Map<String, Object> args = new HashMap<>();
        args.put("value", "hello");

        Object result = found.get().execute(args);
        assertEquals("hello", result);
    }

    @Test
    public void returnsEmptyForUnknownToolName() {
        ToolRegistry registry = new ToolRegistry();

        Optional<Tool> found = registry.findByName("unknown-tool");

        assertFalse(found.isPresent());
        assertNull(found.orElse(null));
    }
}
