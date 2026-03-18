package io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters;

import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Errors;
import io.mateu.workflow.infra.out.persistence.LogMessageEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;

import java.util.List;
import java.util.Optional;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class ErrorCrudAdapter extends AutoListAdapter<Error> {

    final LogMessageEntityRepository repository;
    private String processId;

    public ErrorCrudAdapter withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    @Override
    public CrudRepository<Error> repository() {
        return new CrudRepository<Error>() {
            @Override
            public Optional<Error> findById(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String save(Error entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Error> findAll() {
                return repository.findAllByProcessId(processId).stream()
                        .filter(entity -> "error".equals(entity.getMessageType()))
                        .map(entity -> new Error(processId, entity.getId(), entity.getMessage()))
                        .toList();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                throw new UnsupportedOperationException();
            }
        };
    }

}
