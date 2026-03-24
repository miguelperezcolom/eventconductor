package io.mateu.workflow.contentservice.domain.aggregates.content;


import io.mateu.uidl.interfaces.Identifiable;
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


public static Content of(ContentName name) {
Content p = new Content();
p.name = name;
return p;
}

public void update(ContentName name) {
this.name = name;
}

            }
