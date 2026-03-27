package io.mateu.workflow.contentservice.domain.aggregates.content;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentValue;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Content extends AggregateRoot {

ContentId id;

ContentName name;

ContentTypeId contentType;

List<LabelId> labels;

List<ContentValue> values;


public static Content of(ContentName name, ContentTypeId contentType, List<LabelId> labels, List<ContentValue> values) {
Content p = new Content();
p.name = name;
p.values = values;
p.contentType = contentType;
p.labels = labels;
return p;
}

public void update(ContentName name, ContentTypeId contentType, List<LabelId> labels, List<ContentValue> values) {
    this.name = name;
    this.values = values;
    this.contentType = contentType;
    this.labels = labels;
}

            }
