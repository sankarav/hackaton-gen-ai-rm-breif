package com.demo.rmbrief.tools;

/**
 * Uniform envelope returned by every tool.
 * The agent loop serialises {@code data} to JSON and feeds it to Claude.
 */
public record ToolResult(String toolName, String clientId, Object data, boolean success, String error) {

    public static ToolResult ok(String toolName, String clientId, Object data) {
        return new ToolResult(toolName, clientId, data, true, null);
    }

    public static ToolResult fail(String toolName, String clientId, String error) {
        return new ToolResult(toolName, clientId, null, false, error);
    }
}
