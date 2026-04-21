package io.mateu.workflow.controlplaneservice.domain.aggregates.country;


import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Country extends AggregateRoot {

    CountryCode code;

    CountryName name;

    TierId tierId;


    public static Country of(CountryCode code, CountryName name, TierId tierId) {
        Country p = new Country();
        p.code = code;
        p.name = name;
        p.tierId = tierId;
        return p;
    }

    public void update(CountryName name, TierId tierId) {
        this.name = name;
        this.tierId = tierId;
    }

}
