package wasabi.paciolimc.nativex;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * **Panama FFM** helpers for mods that need native buffers or future downcalls (e.g. audio decode).
 * Keep hot game loops on the JVM heap; use this for I/O edges and profiling-driven paths only.
 */
public final class PanamaBuffers {

    private PanamaBuffers() {
    }

    /**
     * Allocates a zeroed byte region in a confined arena. Caller must close the arena when done.
     */
    public static MemorySegment allocateBytes(Arena arena, long byteSize) {
        return arena.allocate(byteSize);
    }

    public static void putLong(MemorySegment segment, long offset, long value) {
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, value);
    }

    public static long getLong(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
    }
}
