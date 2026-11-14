package dev.mcspb.api;

import dev.mcspb.core.scan.ServerPackBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Tracks one asynchronous build per desktop API instance. */
final class BuildJobs {
    @FunctionalInterface interface Builder {
        ServerPackBuilder.Result build(Path input, Path output, ServerPackBuilder.Progress progress) throws IOException;
    }
    record Snapshot(String id, String state, String stage, long downloadedBytes, long totalBytes,
                    String currentFile, ServerPackBuilder.Result result, String error) { }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Builder builder;
    private volatile Job active;

    BuildJobs() { this((input, output, progress) -> new ServerPackBuilder().build(input, output, progress)); }
    BuildJobs(Builder builder) { this.builder = builder; }

    synchronized Snapshot start(Path input, Path output) {
        if (active != null && !active.done) throw new IllegalStateException("A build is already running");
        jobs.clear();
        Job job = new Job(UUID.randomUUID().toString());
        jobs.put(job.id, job);
        active = job;
        job.future = executor.submit(() -> {
            job.started = true;
            try {
                ServerPackBuilder.Result result = builder.build(input, output,
                        (stage, downloaded, total, current) -> {
                            job.stage = stage;
                            job.downloadedBytes = downloaded;
                            job.totalBytes = total;
                            job.currentFile = current;
                        });
                job.result = result;
                job.state = "completed";
            } catch (Exception e) {
                job.error = e.getMessage() == null ? "Build failed" : e.getMessage();
                job.state = job.cancelRequested ? "cancelled" : "failed";
            } finally { job.done = true; }
        });
        return snapshot(job);
    }

    Snapshot status(String id) {
        Job job = jobs.get(id);
        return job == null ? null : snapshot(job);
    }

    Snapshot cancel(String id) {
        Job job = jobs.get(id);
        if (job == null) return null;
        if (!job.done) {
            job.cancelRequested = true;
            job.state = "cancelling";
            job.future.cancel(true);
            if (!job.started) { job.state = "cancelled"; job.done = true; }
        }
        return snapshot(job);
    }

    private static Snapshot snapshot(Job job) {
        return new Snapshot(job.id, job.state, job.stage, job.downloadedBytes, job.totalBytes,
                job.currentFile, job.result, job.error);
    }

    private static final class Job {
        final String id;
        volatile String state = "running", stage = "preparing", currentFile = "";
        volatile long downloadedBytes, totalBytes;
        volatile ServerPackBuilder.Result result;
        volatile String error;
        volatile Future<?> future;
        volatile boolean done, cancelRequested, started;
        Job(String id) { this.id = id; }
    }
}
