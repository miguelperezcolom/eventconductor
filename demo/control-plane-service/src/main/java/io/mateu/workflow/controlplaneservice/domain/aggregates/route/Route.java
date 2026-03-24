package io.mateu.workflow.controlplaneservice.domain.aggregates.route;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Route extends AggregateRoot {

RouteId id;

RouteName name;


public static Route of(RouteName name) {
Route p = new Route();
p.name = name;
return p;
}

public void update(RouteName name) {
this.name = name;
}

            }
