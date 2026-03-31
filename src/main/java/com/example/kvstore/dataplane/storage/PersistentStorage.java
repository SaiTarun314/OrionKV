package com.example.kvstore.dataplane.storage;

import com.example.kvstore.dataplane.model.WriteAheadLogEntry;

import java.util.List;

public interface PersistentStorage {

    void append(WriteAheadLogEntry entry);

    List<WriteAheadLogEntry> loadAll();
}
