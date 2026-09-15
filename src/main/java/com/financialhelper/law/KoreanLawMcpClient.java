package com.financialhelper.law;

import java.util.Map;

/** Minimal typed boundary for the pinned MCP server. */
public interface KoreanLawMcpClient {

    ServerMetadata metadata();

    ToolResult call(String toolName, Map<String, Object> arguments);

    record ServerMetadata(String name, String version, String protocolVersion) {
    }

    record ToolResult(String toolName, String text) {
        public ToolResult {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName is required");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("tool response text is required");
            }
        }
    }
}
