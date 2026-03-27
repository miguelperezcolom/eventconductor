package io.mateu.workflow.controlplaneservice.domain.aggregates.environment;


import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentName;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Environment extends AggregateRoot {

    EnvironmentId id;

    EnvironmentName name;


    public static Environment of(EnvironmentId id, EnvironmentName name) {
        Environment p = new Environment();
        p.id = id;
        p.name = name;
        return p;
    }

    public void update(EnvironmentName name) {
        this.name = name;
    }

}
