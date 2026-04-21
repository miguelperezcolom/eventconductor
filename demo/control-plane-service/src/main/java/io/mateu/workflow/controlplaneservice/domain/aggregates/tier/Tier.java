package io.mateu.workflow.controlplaneservice.domain.aggregates.tier;


import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.tier.vo.TierParallelThreads;
import io.mateu.workflow.ddd.AggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Tier extends AggregateRoot {

    TierId id;

    TierName name;

    TierParallelThreads parallelThreads;


    public static Tier of(TierId id, TierName name, TierParallelThreads parallelThreads) {
        Tier p = new Tier();
        p.id = id;
        p.name = name;
        p.parallelThreads = parallelThreads;
        return p;
    }

    public void update(TierName name, TierParallelThreads parallelThreads) {
        this.name = name;
        this.parallelThreads = parallelThreads;
    }

}
