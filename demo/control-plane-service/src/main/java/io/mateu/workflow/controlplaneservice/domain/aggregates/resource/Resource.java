package io.mateu.workflow.controlplaneservice.domain.aggregates.resource;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Resource extends AggregateRoot {

ResourceId id;

ResourceName name;


public static Resource of(ResourceName name) {
Resource p = new Resource();
p.name = name;
return p;
}

public void update(ResourceName name) {
this.name = name;
}

            }
