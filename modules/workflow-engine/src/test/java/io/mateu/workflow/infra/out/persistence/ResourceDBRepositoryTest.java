package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.domain.aggregates.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A process resource's trip through its JPA entity: every field survives both ways. */
class ResourceDBRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 18, 9, 30);

    private final ResourceEntityRepository entities = mock(ResourceEntityRepository.class);
    private final ResourceDBRepository repository = new ResourceDBRepository(entities);

    private static ResourceEntity entity(String id, String processId) {
        return new ResourceEntity(id, AT, processId, "se-1", "link", "Invoice", "https://example.com/invoice.pdf");
    }

    private static void assertMapped(Resource resource, String id, String processId) {
        assertThat(resource.getId()).isEqualTo(id);
        assertThat(resource.getTimestamp()).isEqualTo(AT);
        assertThat(resource.getProcessId()).isEqualTo(processId);
        assertThat(resource.getStepExecutionId()).isEqualTo("se-1");
        assertThat(resource.getType()).isEqualTo("link");
        assertThat(resource.getName()).isEqualTo("Invoice");
        assertThat(resource.getUrl()).isEqualTo("https://example.com/invoice.pdf");
    }

    @Test
    void savingStoresEveryFieldAndReturnsTheId() {
        var resource = new Resource("r-1", AT, "p-1", "se-1", "link", "Invoice", "https://example.com/invoice.pdf");

        assertThat(repository.save(resource)).isEqualTo("r-1");

        var captor = ArgumentCaptor.forClass(ResourceEntity.class);
        verify(entities).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved).usingRecursiveComparison().isEqualTo(entity("r-1", "p-1"));
    }

    @Test
    void findsByIdMappingEveryField() {
        when(entities.findById("r-1")).thenReturn(Optional.of(entity("r-1", "p-1")));
        when(entities.findById("nope")).thenReturn(Optional.empty());

        assertMapped(repository.findById("r-1").orElseThrow(), "r-1", "p-1");
        assertThat(repository.findById("nope")).isEmpty();
    }

    @Test
    void listsAllAndByProcess() {
        when(entities.findAll()).thenReturn(List.of(entity("r-1", "p-1"), entity("r-2", "p-2")));
        when(entities.findAllByProcessId("p-2")).thenReturn(List.of(entity("r-2", "p-2")));

        assertThat(repository.findAll()).extracting(Resource::getId).containsExactly("r-1", "r-2");
        assertThat(repository.findByProcessId("p-2")).singleElement()
                .satisfies(r -> assertMapped(r, "r-2", "p-2"));
    }

    @Test
    void deletesTheGivenIds() {
        repository.deleteAllById(List.of("r-1", "r-2"));

        verify(entities).deleteAllById(List.of("r-1", "r-2"));
    }
}
