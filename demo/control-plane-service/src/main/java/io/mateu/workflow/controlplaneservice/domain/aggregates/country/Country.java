package io.mateu.workflow.controlplaneservice.domain.aggregates.country;


import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryName;
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


    public static Country of(CountryCode code, CountryName name) {
        Country p = new Country();
        p.code = code;
        p.name = name;
        return p;
    }

    public void update(CountryName name) {
        this.name = name;
    }

}
