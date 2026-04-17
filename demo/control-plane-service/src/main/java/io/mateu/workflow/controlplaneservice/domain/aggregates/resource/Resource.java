package io.mateu.workflow.controlplaneservice.domain.aggregates.resource;


import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.*;
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

    ResourceStatusCode statusCode;

    ResourceLastUpdated lastUpdated;

    ResourceSize size;

    ResourceMilliseconds milliseconds;


    public static Resource of(ResourceId id,
                              ResourceName name,
                              ResourcePath path,
                              ResourceContent content,
                              ResourceStatusCode statusCode,
                              ResourceLastUpdated lastUpdated,
                              ResourceSize size,
                              ResourceMilliseconds milliseconds) {
        Resource p = new Resource();
        p.id = id;
        p.name = name;
        p.path = path;
        p.content = content;
        p.statusCode = statusCode;
        p.lastUpdated = lastUpdated;
        p.size = size;
        p.milliseconds = milliseconds;

        return p;
    }

    public void update(ResourceName name,
                       ResourcePath path,
                       ResourceContent content,
                       ResourceStatusCode statusCode,
                       ResourceLastUpdated lastUpdated,
                       ResourceSize size,
                       ResourceMilliseconds milliseconds) {
        this.name = name;
        this.path = path;
        this.content = content;
        this.statusCode = statusCode;
        this.lastUpdated = lastUpdated;
        this.size = size;
        this.milliseconds = milliseconds;
    }

}
