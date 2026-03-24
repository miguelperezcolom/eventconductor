package io.mateu.workflow.controlplaneservice.domain.aggregates.site;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Site extends AggregateRoot {

SiteId id;

SiteName name;


public static Site of(SiteName name) {
Site p = new Site();
p.name = name;
return p;
}

public void update(SiteName name) {
this.name = name;
}

            }
