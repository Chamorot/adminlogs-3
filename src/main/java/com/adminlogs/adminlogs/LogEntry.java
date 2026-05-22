package com.adminlogs.adminlogs;

public class LogEntry {

    private final String command;
    private final String timestamp;

    public LogEntry(String command, String timestamp) {
        this.command = command;
        this.timestamp = timestamp;
    }

    public String getCommand() {
        return command;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
