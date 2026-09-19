package network.vonix.threadedhorizons.mixin;

import net.minecraft.server.level.ServerChunkCache;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TickChunksBytecodeTest {

    @Test
    void tickChunksInvokesTickingChunkAndEntitiesNotTickingFuture() throws Exception {
        String disassembly = javap(ServerChunkCache.class);
        String tickChunks = extractMethod(disassembly, "private void tickChunks();");
        String isPositionTicking = extractMethod(disassembly, "public boolean isPositionTicking(long);");
        assertTrue(tickChunks.contains("getTickingChunk:()Lnet/minecraft/world/level/chunk/LevelChunk;"),
                "tickChunks must call getTickingChunk");
        assertTrue(tickChunks.contains("getAllEntities:()Ljava/lang/Iterable;"),
                "tickChunks must call getAllEntities");
        assertFalse(tickChunks.contains("getTickingChunkFuture:()Ljava/util/concurrent/CompletableFuture;"),
                "tickChunks must not call getTickingChunkFuture on official 1.18.2");
        assertTrue(isPositionTicking.contains("getTickingChunkFuture:()Ljava/util/concurrent/CompletableFuture;"),
                "isPositionTicking is the official getTickingChunkFuture owner");
    }

    static String javap(Class<?> type) throws Exception {
        URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path jar = Path.of(location);
        String javap = Path.of(System.getProperty("java.home"), "bin", "javap").toString();
        Process process = new ProcessBuilder(javap, "-c", "-p", "-classpath", jar.toString(), type.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            fail("javap failed: " + output);
        }
        return output;
    }

    static String extractMethod(String disassembly, String header) {
        int start = disassembly.indexOf(header);
        assertTrue(start >= 0, "missing " + header);
        int searchFrom = start + header.length();
        int next = -1;
        int cursor = searchFrom;
        while (cursor < disassembly.length()) {
            int line = disassembly.indexOf('\n', cursor);
            if (line < 0) {
                break;
            }
            int content = line + 1;
            if (content + 2 < disassembly.length()
                    && disassembly.charAt(content) == ' '
                    && disassembly.charAt(content + 1) == ' '
                    && disassembly.charAt(content + 2) != ' ') {
                next = line;
                break;
            }
            cursor = line + 1;
        }
        if (next < 0) {
            return disassembly.substring(start);
        }
        return disassembly.substring(start, next);
    }
}
