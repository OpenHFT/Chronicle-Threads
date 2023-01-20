package net.openhft.chronicle.threads;

import java.nio.file.FileStore;

public interface NotifyDiskLow {
    void panic(FileStore fileStore);

    void warning(double diskSpaceFullPercent, FileStore fileStore);
}
