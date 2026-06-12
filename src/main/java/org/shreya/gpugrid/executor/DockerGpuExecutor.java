package org.shreya.gpugrid.executor;

import org.shreya.gpugrid.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Production GpuExecutor: delegates to the Docker daemon.
 *
 * <p>Each job is launched as a Docker container with the NVIDIA runtime device
 * flag {@code --gpus device=N}. The container ID returned by {@code docker run}
 * becomes the {@code executionId} stored in {@link org.shreya.gpugrid.job.Job#containerId()}.
 *
 * <p>Required application.properties keys:
 * <pre>
 *   gpugrid.docker.image          – Docker image to run (e.g. nvidia/cuda:12.3-base)
 *   gpugrid.docker.gpu-count      – Number of GPUs to advertise via listAvailableGpus()
 *   gpugrid.docker.extra-args     – (optional) space-separated extra docker-run flags
 * </pre>
 */
@Component
@Profile("prod")
public class DockerGpuExecutor implements GpuExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerGpuExecutor.class);

    @Value("${gpugrid.docker.image:nvidia/cuda:12.3-base}")
    private String dockerImage;

    @Value("${gpugrid.docker.gpu-count:4}")
    private int gpuCount;

    /**
     * Optional extra args injected verbatim after {@code docker run}, e.g.
     * {@code --network=host --shm-size=8g}.  Space-delimited.
     */
    @Value("${gpugrid.docker.extra-args:}")
    private String extraArgs;

    // -------------------------------------------------------------------------
    // GpuExecutor API
    // -------------------------------------------------------------------------

    /**
     * Runs:
     * <pre>
     *   docker run -d --gpus device=N [extra-args] IMAGE sleep infinity
     * </pre>
     * and captures the full container ID from stdout.
     *
     * @param job      the job being started (used for labelling the container)
     * @param gpuIndex 0-based GPU index passed to {@code --gpus device=N}
     * @return the 64-character Docker container ID
     */
    @Override
    public String startJob(Job job, int gpuIndex) {
        List<String> cmd = buildRunCommand(job, gpuIndex);
        log.info("DockerGpuExecutor.startJob: {}", String.join(" ", cmd));

        String containerId = runCommand(cmd).trim();
        if (containerId.isEmpty()) {
            throw new IllegalStateException(
                    "docker run produced no output — container may not have started");
        }
        log.info("Job bookingId={} started → containerId={}", job.bookingId(), containerId);
        return containerId;
    }

    /**
     * Runs {@code docker stop <id>} (sends SIGTERM, waits 10 s, then SIGKILL).
     */
    @Override
    public void stopJob(String executionId) {
        log.info("DockerGpuExecutor.stopJob: stopping container {}", executionId);
        runCommand(List.of("docker", "stop", executionId));
    }

    /**
     * Runs:
     * <pre>
     *   docker inspect --format '{{.State.Status}}' &lt;id&gt;
     * </pre>
     * and maps the Docker state string to {@link JobExecutionStatus}.
     *
     * <p>Docker state → JobExecutionStatus mapping:
     * <ul>
     *   <li>{@code running}  → RUNNING</li>
     *   <li>{@code exited} with exit-code 0 → COMPLETED</li>
     *   <li>{@code exited} with exit-code != 0, {@code dead}, {@code oomkilled},
     *       or anything unknown → FAILED</li>
     * </ul>
     */
    @Override
    public JobExecutionStatus getStatus(String executionId) {
        // First get the high-level state
        String state = runCommand(
                List.of("docker", "inspect",
                        "--format", "{{.State.Status}}",
                        executionId)
        ).trim().toLowerCase();

        log.debug("DockerGpuExecutor.getStatus: containerId={} → dockerState={}", executionId, state);

        return switch (state) {
            case "running", "restarting", "paused" -> JobExecutionStatus.RUNNING;
            case "exited" -> resolveExitCode(executionId);
            case "created" -> JobExecutionStatus.RUNNING;   // not yet started but accepted
            default -> {
                log.warn("Unrecognised docker state '{}' for container {}", state, executionId);
                yield JobExecutionStatus.FAILED;
            }
        };
    }

    /**
     * Lists GPUs by querying the NVIDIA container CLI if available, falling back
     * to synthesising {@code gpuCount} entries.
     */
    @Override
    public List<GpuInfo> listAvailableGpus() {
        try {
            return queryNvidiaGpus();
        } catch (Exception e) {
            log.warn("nvidia-smi query failed ({}); falling back to config-based list", e.getMessage());
            return syntheticGpuList();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Builds the full {@code docker run} command list. */
    private List<String> buildRunCommand(Job job, int gpuIndex) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--detach");                        // -d: print container ID, don't attach
        cmd.add("--gpus");
        cmd.add("device=" + gpuIndex);             // pin to exactly one GPU
        cmd.add("--label");
        cmd.add("gpugrid.job.id=" + job.id());
        cmd.add("--label");
        cmd.add("gpugrid.booking.id=" + job.bookingId());
        cmd.add("--name");
        cmd.add("gpugrid-job-" + job.id());

        // Inject optional extra flags (split on whitespace, skip blanks)
        if (extraArgs != null && !extraArgs.isBlank()) {
            for (String arg : extraArgs.trim().split("\\s+")) {
                cmd.add(arg);
            }
        }

        cmd.add(dockerImage);
        cmd.add("sleep");
        cmd.add("infinity");        // container stays alive; real workloads override this

        return cmd;
    }

    /**
     * Checks the exit code of a container that is in the {@code exited} state.
     * Exit code 0 → COMPLETED; anything else → FAILED.
     */
    private JobExecutionStatus resolveExitCode(String executionId) {
        String exitCodeStr = runCommand(
                List.of("docker", "inspect",
                        "--format", "{{.State.ExitCode}}",
                        executionId)
        ).trim();

        try {
            int code = Integer.parseInt(exitCodeStr);
            if (code == 0) {
                log.debug("Container {} exited cleanly (code 0) → COMPLETED", executionId);
                return JobExecutionStatus.COMPLETED;
            } else {
                log.warn("Container {} exited with code {} → FAILED", executionId, code);
                return JobExecutionStatus.FAILED;
            }
        } catch (NumberFormatException ex) {
            log.error("Could not parse exit code '{}' for container {}", exitCodeStr, executionId);
            return JobExecutionStatus.FAILED;
        }
    }

    /**
     * Queries GPU names from nvidia-smi and builds a {@link GpuInfo} list.
     * Throws if nvidia-smi is unavailable or returns non-zero.
     */
    private List<GpuInfo> queryNvidiaGpus() throws IOException, InterruptedException {
        List<String> cmd = List.of(
                "nvidia-smi",
                "--query-gpu=index,name,memory.free",
                "--format=csv,noheader,nounits"
        );
        String output = runCommand(cmd);
        List<GpuInfo> gpus = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] parts = line.split(",");
            if (parts.length < 2) continue;
            int idx = Integer.parseInt(parts[0].trim());
            String name = parts[1].trim();
            long freeMb = parts.length > 2 ? Long.parseLong(parts[2].trim()) : 0L;
            gpus.add(new GpuInfo(idx, name, freeMb > 0));
        }
        return gpus;
    }

    /** Fallback: return synthetic GpuInfo entries based on {@code gpuCount}. */
    private List<GpuInfo> syntheticGpuList() {
        List<GpuInfo> list = new ArrayList<>();
        for (int i = 0; i < gpuCount; i++) {
            list.add(new GpuInfo(i, "GPU-" + i, true));
        }
        return list;
    }

    /**
     * Executes a command via {@link ProcessBuilder}, waits for it to exit, and
     * returns stdout as a trimmed string.  Stderr is logged at WARN level.
     *
     * @throws RuntimeException wrapping any {@link IOException} or if the process
     *                          exits with a non-zero status code
     */
    private String runCommand(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);          // keep stdout / stderr separate
            Process process = pb.start();

            String stdout;
            String stderr;
            try (BufferedReader outReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                 BufferedReader errReader = new BufferedReader(
                         new InputStreamReader(process.getErrorStream()))) {
                stdout = outReader.lines().collect(Collectors.joining("\n"));
                stderr = errReader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();

            if (!stderr.isBlank()) {
                log.warn("docker stderr [{}]: {}", String.join(" ", cmd), stderr);
            }
            if (exitCode != 0) {
                throw new RuntimeException(
                        "Command %s exited with code %d. stderr: %s"
                                .formatted(String.join(" ", cmd), exitCode, stderr));
            }
            return stdout;

        } catch (IOException e) {
            throw new RuntimeException("Failed to launch command: " + String.join(" ", cmd), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for command: " + String.join(" ", cmd), e);
        }
    }
}