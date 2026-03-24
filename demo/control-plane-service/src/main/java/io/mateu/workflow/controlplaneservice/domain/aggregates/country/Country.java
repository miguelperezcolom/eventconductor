package io.mateu.workflow.controlplaneservice.domain.aggregates.country;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Country extends AggregateRoot {

CountryId id;

CountryName name;


public static Country of(CountryName name) {
Country p = new Country();
p.name = name;
return p;
}

public void update(CountryName name) {
this.name = name;
}

            }
