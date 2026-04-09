package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.asset;

import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetDto;
import io.mateu.workflow.controlplaneservice.application.usecases.asset.create.CreateAssetCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.asset.create.CreateAssetUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.asset.update.UpdateAssetCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.asset.update.UpdateAssetUseCase;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.CountryIdLabelSupplier;
import io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers.CountryIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class AssetViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
    @HiddenInCreate
    @ReadOnly
    String id;
    @NotEmpty
    String name;
    @NotEmpty
    String path;
    @NotEmpty
    String url;
    @Lookup(search = CountryIdOptionsSupplier.class, label = CountryIdLabelSupplier.class)
    String country;

    final CreateAssetUseCase createAssetUseCase;
    final UpdateAssetUseCase updateAssetUseCase;

    @Override
    public String create(HttpRequest httpRequest) {
        return createAssetUseCase.handle(new CreateAssetCommand(name, path, url, country));
    }

    @Override
    public void save(HttpRequest httpRequest) {
        updateAssetUseCase.handle(new UpdateAssetCommand(id, name, path, url, country));
    }

    @Override
    public String id() {
        return id;
    }

    public AssetViewModel load(AssetDto asset) {
        id = String.valueOf(asset.id());
        name = asset.name();
        path = asset.path();
        url = asset.url();
        country = asset.countryCode();
        return this;
    }

    @Override
    public String toString() {
        return id != null ? name : "New asset";
    }
}
