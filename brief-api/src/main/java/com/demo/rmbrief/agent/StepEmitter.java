package com.demo.rmbrief.agent;

import com.demo.rmbrief.delta.ChangeReport;

/** Callback used by AgentOrchestrator to push progress events to the SSE layer. */
public interface StepEmitter {
    void step(String type, String message);
    void delta(ChangeReport report);
    void brief(Object briefJson);
    void error(String message);
    void complete();
}
