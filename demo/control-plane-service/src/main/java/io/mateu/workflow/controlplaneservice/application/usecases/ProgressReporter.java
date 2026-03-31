package io.mateu.workflow.controlplaneservice.application.usecases;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Error;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Message;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Resource;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;

import java.time.LocalDateTime;
import java.util.List;

public interface ProgressReporter {

    List<Step> getSteps();

    List<Message> getMessages();

    List<Error> getErrors();

    List<Resource> getResources();

    default void update(int stepIndex, StatusType status) {
        var step = getSteps().get(stepIndex);
        getSteps().set(stepIndex, new Step(step.processId(), step.id(), step.name(), new Status(status, switch (status) {
            case SUCCESS -> "Complete";
            case WARNING -> "Running";
            case DANGER -> "Error";
            default -> "Pending";
        })));
    }

    default void log(String message) {
        System.out.println(message);
        getMessages().addFirst(new Message("x", "" + getMessages().size(), LocalDateTime.now(), message));
    }

    default void reset() {
        getSteps().clear();
        getMessages().clear();
        getErrors().clear();
        getResources().clear();
    }

    default void failed() {
       for (var i = 0; i < getSteps().size(); i++) {
           var step = getSteps().get(i);
           if (step.status().type().equals(StatusType.INFO)) {
               getSteps().set(i, new Step(step.processId(), step.id(), step.name(), new Status(StatusType.NONE, "Cancelled")));
           }
       }
    }

}
