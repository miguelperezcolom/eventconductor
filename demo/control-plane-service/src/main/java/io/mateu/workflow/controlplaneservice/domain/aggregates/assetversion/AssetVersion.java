package io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class AssetVersion extends AggregateRoot {

AssetVersionId id;

AssetVersionName name;


public static AssetVersion of(AssetVersionName name) {
AssetVersion p = new AssetVersion();
p.name = name;
return p;
}

public void update(AssetVersionName name) {
this.name = name;
}

            }
