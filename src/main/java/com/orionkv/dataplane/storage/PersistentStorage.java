package com.orionkv.dataplane.storage;

import com.orionkv.dataplane.model.WriteAheadLogEntry;

import java.util.List;

public interface PersistentStorage {

    void append(WriteAheadLogEntry entry);

    List<WriteAheadLogEntry> loadAll();
}
