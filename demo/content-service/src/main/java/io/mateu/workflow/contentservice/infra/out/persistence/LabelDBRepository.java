package io.mateu.workflow.contentservice.infra.out.persistence;

import io.mateu.workflow.contentservice.application.out.LabelRepository;
import io.mateu.workflow.contentservice.domain.aggregates.label.Label;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class LabelDBRepository implements LabelRepository {

final LabelEntityRepository repository;

@Override
public Optional<Label> findById(LabelId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private Label toDomain(LabelEntity entity) {
    return new Label(
    new LabelId(entity.id),
    new LabelName(entity.name)
    );
    }

    private LabelEntity toEntity(Label label) {
    return new LabelEntity(
label.getId() != null?Long.valueOf(label.getId().id()):null,
label.getName().name()
    );
    }

    @Override
    public LabelId save(Label label) {
    return new LabelId(repository.save(toEntity(label)).id);
    }

    @Override
    public void deleteAllById(List<LabelId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(LabelId::id).toList());
        }
        }
