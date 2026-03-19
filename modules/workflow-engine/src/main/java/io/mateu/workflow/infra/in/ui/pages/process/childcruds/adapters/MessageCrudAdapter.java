package io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters;


import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Message;
import io.mateu.workflow.infra.out.persistence.LogMessageEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MessageCrudAdapter extends AutoListAdapter<Message> {

    final LogMessageEntityRepository repository;
    private String processId;

    public MessageCrudAdapter withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    @Override
    public CrudRepository<Message> repository() {
        return new CrudRepository<Message>() {
            @Override
            public Optional<Message> findById(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String save(Message entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Message> findAll() {
                return repository.findAllByProcessId(processId).stream()
                        .filter(entity -> !"error".equals(entity.getMessageType()))
                        .map(entity -> new Message(processId, entity.getId(), entity.getTimestamp(), entity.getMessage()))
                        .toList();
            }

            @Override
            public void deleteAllById(List<String> selectedIds) {
                throw new UnsupportedOperationException();
            }
        };
    }

}
