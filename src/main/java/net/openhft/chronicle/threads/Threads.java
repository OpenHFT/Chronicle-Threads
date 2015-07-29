/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.ForceInline;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;

/**
 * Created by peter on 24/06/15.
 */
public enum Threads {
    ;

    static final Field GROUP = Jvm.getField(Thread.class, "group");

    @ForceInline
    public static <R> R withThreadGroup(ThreadGroup tg, @NotNull Callable<R> callable) {
        Thread thread = Thread.currentThread();
        ThreadGroup tg0 = thread.getThreadGroup();
        setThreadGroup(thread, tg);
        try {
            return callable.call();
        } catch (Exception e) {
            throw Jvm.rethrow(e);
        } finally {
            setThreadGroup(thread, tg0);
        }
    }

    @ForceInline
    public static void setThreadGroup(Thread thread, ThreadGroup tg) {
        try {
            GROUP.set(thread, tg);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @NotNull
    public static String threadGroupPrefix() {
        String threadGroupName = Thread.currentThread().getThreadGroup().getName();
        if (!threadGroupName.endsWith("/"))
            threadGroupName += "/";
        return threadGroupName;
    }
}
