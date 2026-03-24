package io.mateu.workflow.controlplaneservice.domain.aggregates.release;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Release extends AggregateRoot {

ReleaseId id;

ReleaseName name;


public static Release of(ReleaseName name) {
Release p = new Release();
p.name = name;
return p;
}

public void update(ReleaseName name) {
this.name = name;
}

            }
