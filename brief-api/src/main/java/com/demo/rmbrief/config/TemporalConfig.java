package com.demo.rmbrief.config;

import com.demo.rmbrief.capture.workflow.CaptureActivitiesImpl;
import com.demo.rmbrief.capture.workflow.CaptureWorkflow;
import com.demo.rmbrief.capture.workflow.CaptureWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** Plain Temporal SDK wiring; the worker runs in-process and starts once the app is ready. */
@Configuration
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(@Value("${app.temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, @Value("${app.temporal.namespace}") String namespace) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient client, CaptureActivitiesImpl activities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(CaptureWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(CaptureWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return factory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorkers(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(WorkerFactory.class).start();
    }
}
