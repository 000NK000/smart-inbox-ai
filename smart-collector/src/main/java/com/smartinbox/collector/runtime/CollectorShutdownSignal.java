package com.smartinbox.collector.runtime;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancel source reads at safe boundaries, never interrupt an MQ send/checkpoint. */
@Component
public class CollectorShutdownSignal {
    private final AtomicBoolean stopping = new AtomicBoolean();

    @EventListener(ContextClosedEvent.class)
    public void stop() { stopping.set(true); }

    public boolean isStopping() { return stopping.get(); }

    public void check() {
        if (isStopping()) throw new CancellationException("collector_stopping");
    }
}
