package io.mateu.workflow.controlplaneservice.domain.aggregates.asset;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.asset.vo.AssetName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Asset extends AggregateRoot {

AssetId id;

AssetName name;


public static Asset of(AssetName name) {
Asset p = new Asset();
p.name = name;
return p;
}

public void update(AssetName name) {
this.name = name;
}

            }
