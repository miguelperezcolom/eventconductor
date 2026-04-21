package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page;

import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.CheckType;

public record PageCheckViewModel(CheckType checkType, String value) {
}
