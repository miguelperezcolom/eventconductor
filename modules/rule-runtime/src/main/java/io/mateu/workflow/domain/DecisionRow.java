package io.mateu.workflow.domain;

import java.util.List;

public record DecisionRow(List<String> when, List<String> then) {
}
