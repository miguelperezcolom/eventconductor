package io.mateu.workflow.controlplaneservice.domain.aggregates.asset;


import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetPath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetUrl;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.Country;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Asset extends AggregateRoot {

    AssetId id;

    AssetName name;

    AssetPath path;

    AssetUrl url;

    CountryCode country;


    public static Asset of(AssetName name, AssetPath path, AssetUrl url, CountryCode country) {
        Asset p = new Asset();
        p.name = name;
        p.path = path;
        p.url = url;
        p.country = country;
        return p;
    }

    public void update(AssetName name, AssetPath path, AssetUrl url, CountryCode country) {
        this.name = name;
        this.path = path;
        this.url = url;
        this.country = country;
    }

}
