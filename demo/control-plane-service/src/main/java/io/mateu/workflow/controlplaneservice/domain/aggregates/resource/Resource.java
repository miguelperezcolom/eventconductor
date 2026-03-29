package io.mateu.workflow.controlplaneservice.domain.aggregates.resource;


import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceContent;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourcePath;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
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

    public void update(ResourceName name, ResourcePath path, ResourceContent content) {
        this.name = name;
        this.path = path;
        this.content = content;
    }

}
