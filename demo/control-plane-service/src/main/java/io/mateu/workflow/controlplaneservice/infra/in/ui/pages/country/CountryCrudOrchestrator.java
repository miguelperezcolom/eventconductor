package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Countries")
public class CountryCrudOrchestrator extends CrudOrchestrator<
CountryViewModel,
CountryViewModel,
CountryViewModel,
NoFilters,
CountryRow,
String
> {

final CountryCrudAdapter adapter;

@Override
public CrudAdapter<CountryViewModel,
CountryViewModel, CountryViewModel,
NoFilters, CountryRow, String> adapter() {
return adapter;
}

@Override
public String toId(String s) {
return s;
}
}
