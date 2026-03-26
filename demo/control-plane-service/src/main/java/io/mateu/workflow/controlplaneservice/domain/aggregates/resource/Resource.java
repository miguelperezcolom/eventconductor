package io.mateu.workflow.controlplaneservice.domain.aggregates.resource;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceContent;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourcePath;
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

    ResourcePath path;

ResourceContent content;


public static Resource of(ResourceId id, ResourceName name, ResourcePath path, ResourceContent content) {
Resource p = new Resource();
p.id = id;
p.name = name;
p.path = path;
p.content = content;

return p;
}

public void update(ResourceName name) {
this.name = name;
}

            }
