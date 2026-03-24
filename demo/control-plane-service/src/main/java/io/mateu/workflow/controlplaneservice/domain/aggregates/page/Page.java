package io.mateu.workflow.controlplaneservice.domain.aggregates.page;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Page extends AggregateRoot {

PageId id;

PageName name;


public static Page of(PageName name) {
Page p = new Page();
p.name = name;
return p;
}

public void update(PageName name) {
this.name = name;
}

            }
