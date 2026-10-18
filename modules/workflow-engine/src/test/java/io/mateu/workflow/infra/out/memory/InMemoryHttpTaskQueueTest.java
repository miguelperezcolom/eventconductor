package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.httpworkers.HttpTaskQueue;
import io.mateu.workflow.application.httpworkers.HttpTaskQueueContract;

class InMemoryHttpTaskQueueTest extends HttpTaskQueueContract {

    @Override
    protected HttpTaskQueue newQueue() {
        return new InMemoryHttpTaskQueue();
    }
}
