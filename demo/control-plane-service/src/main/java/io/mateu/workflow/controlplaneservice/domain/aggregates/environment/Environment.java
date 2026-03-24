package io.mateu.workflow.controlplaneservice.domain.aggregates.environment;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Environment extends AggregateRoot {

EnvironmentId id;

EnvironmentName name;


public static Environment of(EnvironmentName name) {
Environment p = new Environment();
p.name = name;
return p;
}

public void update(EnvironmentName name) {
this.name = name;
}

            }
