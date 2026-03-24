package io.mateu.workflow.controlplaneservice.domain.aggregates.language;


import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Language extends AggregateRoot {

LanguageId id;

LanguageName name;


public static Language of(LanguageName name) {
Language p = new Language();
p.name = name;
return p;
}

public void update(LanguageName name) {
this.name = name;
}

            }
