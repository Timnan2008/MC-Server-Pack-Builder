package dev.mcspb.api;

import dev.mcspb.core.scan.ServerPackBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildJobsTest {
    @Test void reportsActualBuilderProgressAndCompletion() throws Exception {
        BuildJobs jobs = new BuildJobs((input, output, progress) -> {
            progress.update("downloading", 512, 1024, "mods/example.jar");
            return new ServerPackBuilder.Result(output.toString(), "1.21", "21.1", 1, 1, 0, List.of());
        });
        String id = jobs.start(Path.of("input.mrpack"), Path.of("output.zip")).id();
        BuildJobs.Snapshot result = awaitDone(jobs, id);
        assertEquals("completed", result.state());
        assertEquals(512, result.downloadedBytes());
        assertEquals(1024, result.totalBytes());
        assertEquals("mods/example.jar", result.currentFile());
        assertNotNull(result.result());
    }

    @Test void cancelsRunningBuilder() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        BuildJobs jobs = new BuildJobs((input, output, progress) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Build cancelled", e); }
            throw new AssertionError("Unreachable");
        });
        String id = jobs.start(Path.of("input.mrpack"), Path.of("output.zip")).id();
        assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
        jobs.cancel(id);
        assertEquals("cancelled", awaitDone(jobs, id).state());
    }

    private static BuildJobs.Snapshot awaitDone(BuildJobs jobs, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            BuildJobs.Snapshot snapshot = jobs.status(id);
            if (snapshot.state().equals("completed") || snapshot.state().equals("failed") || snapshot.state().equals("cancelled"))
                return snapshot;
            Thread.sleep(10);
        }
        fail("Build did not finish in time");
        return null;
    }
}
