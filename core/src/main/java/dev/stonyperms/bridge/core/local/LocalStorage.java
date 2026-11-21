package dev.stonyperms.bridge.core.local;

import dev.stonyperms.bridge.core.GroupRecord;
import dev.stonyperms.bridge.core.LogEntry;
import dev.stonyperms.bridge.core.TrackRecord;
import dev.stonyperms.bridge.core.UserRecord;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Persistence contract implemented by both flat-file JSON storage and
 * relational SQL backends (SQLite, MySQL/MariaDB, PostgreSQL).
 */
public interface LocalStorage extends AutoCloseable {

    String type();

    /** Returns a redacted storage target descriptor safe for logs and /sp status. */
    String describeTarget();

    JsonLocalStorage.DataBundle loadAll() throws IOException;

    void saveUser(UserRecord user) throws IOException;

    void deleteUser(UUID id) throws IOException;

    void saveGroup(GroupRecord group) throws IOException;

    void deleteGroup(String name) throws IOException;

    void saveTrack(TrackRecord track) throws IOException;

    void deleteTrack(String name) throws IOException;

    void appendLog(LogEntry entry) throws IOException;

    List<LogEntry> getLogs(int limit) throws IOException;

    @Override
    void close() throws IOException;
}
