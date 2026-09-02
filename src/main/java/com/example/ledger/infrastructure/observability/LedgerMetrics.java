package com.example.ledger.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Deliberately a thin, explicit wrapper rather than scattering
 * meterRegistry.counter("...") string literals across the codebase - one
 * place to see every custom metric this system exposes, matching the list
 * in design brief section 20.
 *
 * All of these are exposed to Prometheus automatically once
 * micrometer-registry-prometheus + actuator's /actuator/prometheus endpoint
 * are on the classpath (see application.yml `management` section) - no
 * extra wiring needed beyond calling these methods at the right call sites.
 */
@Component
public class LedgerMetrics {

    private final Counter commandsProcessed;
    private final Counter commandsFailed;
    private final Counter snapshotsCreated;
    private final Counter dltMessages;
    private final Timer eventAppendLatency;
    private final Timer timeTravelQueryLatency;
    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.commandsProcessed = Counter.builder("ledger.commands.processed")
                .description("Commands that successfully produced an event")
                .register(registry);
        this.commandsFailed = Counter.builder("ledger.commands.failed")
                .description("Commands rejected by business rules or concurrency conflicts")
                .register(registry);
        this.snapshotsCreated = Counter.builder("ledger.snapshots.created")
                .description("Snapshots written")
                .register(registry);
        this.dltMessages = Counter.builder("ledger.kafka.dlt.messages")
                .description("Messages routed to the dead-letter topic")
                .register(registry);
        this.eventAppendLatency = Timer.builder("ledger.event.append.latency")
                .description("Time to append a single event to the event store")
                .register(registry);
        this.timeTravelQueryLatency = Timer.builder("ledger.timetravel.query.latency")
                .description("Time to reconstruct historical state for a time-travel query")
                .register(registry);
    }

    public void commandProcessed() { commandsProcessed.increment(); }
    public void commandFailed(String reason) {
        commandsFailed.increment();
        registry.counter("ledger.commands.failed.by_reason", "reason", reason).increment();
    }
    public void snapshotCreated() { snapshotsCreated.increment(); }
    public void dltMessage() { dltMessages.increment(); }

    public <T> T recordAppend(java.util.function.Supplier<T> operation) {
        return eventAppendLatency.record(operation);
    }

    public <T> T recordTimeTravelQuery(java.util.function.Supplier<T> operation) {
        return timeTravelQueryLatency.record(operation);
    }
}
