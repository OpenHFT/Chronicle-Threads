/*
 * Copyright 2016-2020 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import java.nio.file.FileStore;

/**
 * The {@code NotifyDiskLow} interface provides methods to notify the system or user
 * when disk space is running low. Implementations of this interface can define specific
 * actions to be taken when disk space reaches a warning or critical level.
 */
public interface NotifyDiskLow {

    /**
     * Called when the available disk space on the specified {@link FileStore} reaches
     * a critical level, requiring immediate action.
     *
     * @param fileStore the {@link FileStore} where disk space is critically low
     */
    void panic(FileStore fileStore);

    /**
     * Called when the available disk space on the specified {@link FileStore} reaches a warning threshold.
     * Allows for preventive actions to avoid reaching critical levels.
     *
     * @param diskSpaceFullPercent the percentage of disk space that is currently full
     * @param fileStore            the {@link FileStore} where disk space is low
     */
    void warning(double diskSpaceFullPercent, FileStore fileStore);
}
