package io.mateu.workflow.contentservice.domain.aggregates.label;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Label extends AggregateRoot {

LabelId id;

LabelName name;


public static Label of(LabelName name) {
Label p = new Label();
p.name = name;
return p;
}

public void update(LabelName name) {
this.name = name;
}

            }
