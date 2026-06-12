package io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters;

import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resource;
import io.mateu.workflow.infra.out.persistence.ResourceEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ResourceCrudAdapter extends AutoCrudAdapter<Resource> {

    final ResourceEntityRepository repository;
    private String processId;

    public ResourceCrudAdapter withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    @Override
    public CrudRepository<Resource> repository() {
        return new CrudRepository<Resource>() {
            @Override
            public Optional<Resource> findById(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String save(Resource entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Resource> findAll() {
                return repository.findAllByProcessId(processId).stream()
                        .map(entity -> new Resource(processId, entity.getId(), entity.getName(), entity.getUrl()))
                        .toList();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                throw new UnsupportedOperationException();
            }
        };
    }

}
