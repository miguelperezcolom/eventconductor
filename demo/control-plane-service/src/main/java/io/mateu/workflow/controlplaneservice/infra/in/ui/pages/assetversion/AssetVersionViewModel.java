package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.assetversion;

import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetVersionDto;
import io.mateu.workflow.controlplaneservice.application.usecases.assetversion.create.CreateAssetVersionCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.assetversion.create.CreateAssetVersionUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.assetversion.update.UpdateAssetVersionCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.assetversion.update.UpdateAssetVersionUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.AssetVersion;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class AssetVersionViewModel implements Identifiable, CrudEditorForm<String>, CrudCreationForm<String> {
        @HiddenInCreate
        @ReadOnly
        String id;
        @NotEmpty String name;

        final CreateAssetVersionUseCase createAssetVersionUseCase;
        final UpdateAssetVersionUseCase updateAssetVersionUseCase;

        @Override
        public String create(HttpRequest httpRequest) {
        return createAssetVersionUseCase.handle(new CreateAssetVersionCommand(name));
        }

        @Override
        public void save(HttpRequest httpRequest) {
        updateAssetVersionUseCase.handle(new UpdateAssetVersionCommand(id, name));
        }

        @Override
        public String id() {
        return id;
        }

        public AssetVersionViewModel load(AssetVersionDto assetversion) {
        id = String.valueOf(assetversion.id());
        name = assetversion.name();
        return this;
        }

        @Override
        public String toString() {
        return id != null ? name : "New assetversion";
        }
        }
