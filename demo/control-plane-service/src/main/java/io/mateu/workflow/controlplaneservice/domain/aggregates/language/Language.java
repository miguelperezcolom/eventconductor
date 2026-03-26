package io.mateu.workflow.controlplaneservice.domain.aggregates.language;


import io.mateu.workflow.ddd.AggregateRoot;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor@AllArgsConstructor
@Getter
public class Language extends AggregateRoot {

LanguageCode code;

LanguageName name;


public static Language of(LanguageCode code, LanguageName name) {
Language p = new Language();
p.code = code;
p.name = name;
return p;
}

public void update(LanguageName name) {
this.name = name;
}

            }
