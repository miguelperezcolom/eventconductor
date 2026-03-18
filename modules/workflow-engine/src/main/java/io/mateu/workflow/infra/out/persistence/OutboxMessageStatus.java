package io.mateu.workflow.infra.out.persistence;

public enum OutboxMessageStatus {
    Pending, Sent, Error
}
