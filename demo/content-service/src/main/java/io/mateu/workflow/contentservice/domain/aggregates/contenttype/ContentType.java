package io.mateu.workflow.contentservice.domain.aggregates.contenttype;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class ContentType extends AggregateRoot {

ContentTypeId id;

ContentTypeName name;


public static ContentType of(ContentTypeName name) {
ContentType p = new ContentType();
p.name = name;
return p;
}

public void update(ContentTypeName name) {
this.name = name;
}

            }
