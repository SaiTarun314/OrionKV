package com.example.kvstore.dataplane.storage;

import com.example.kvstore.dataplane.exception.StorageInitializationException;
import com.example.kvstore.dataplane.model.WriteAheadLogEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

@Component
public class WriteAheadLogRepository implements PersistentStorage {

    private final Path logPath;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public WriteAheadLogRepository(
        @Value("${dataplane.storage.log-path:data/wal.log}") String logPath,
        ObjectMapper objectMapper
    ) {
        this.logPath = Path.of(logPath);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        try {
            Path parentDirectory = logPath.getParent();
            if (parentDirectory != null) {
                Files.createDirectories(parentDirectory);
            }

            if (Files.notExists(logPath)) {
                Files.createFile(logPath);
            }

        } catch (IOException exception) {
            throw new StorageInitializationException("Failed to initialize write-ahead log", exception);
        }
    }

    @Override
    public void append(WriteAheadLogEntry entry) {
        synchronized (writeLock) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                logPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )) {
                writer.write(objectMapper.writeValueAsString(entry));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                throw new StorageInitializationException("Failed to append to write-ahead log", exception);
            }
        }
    }

    @Override
    public List<WriteAheadLogEntry> loadAll() {
        try (Stream<String> lines = Files.lines(logPath, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                .map(this::deserialize)
                .toList();
        } catch (IOException exception) {
            throw new StorageInitializationException("Failed to replay write-ahead log", exception);
        }
    }

    private WriteAheadLogEntry deserialize(String line) {
        try {
            return objectMapper.readValue(line, WriteAheadLogEntry.class);
        } catch (JsonProcessingException exception) {
            throw new StorageInitializationException("Encountered malformed log entry during recovery", exception);
        }
    }
}
