package io.mateu.workflow.infra.out.persistence;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.services.FormValidator;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the behaviour the jpa path owes the in-memory one. These are not hypotheticals: until
 * {@code forms.persistence} was set, forms-standalone-app ran on the in-memory repositories and none
 * of this code had ever executed against a database in production.
 */
@DataJpaTest(properties = "forms.persistence=jpa")
@Import({FormDBRepository.class, FormValidator.class})
class FormDBRepositoryTest {

    @Autowired
    FormDBRepository repository;

    @Autowired
    FieldEntityRepository fieldEntityRepository;

    @BeforeEach
    void setUp() {
        fieldEntityRepository.deleteAll();
    }

    private Field field(String id, String label) {
        return new Field(id, label, FieldDataType.string, FieldStereotype.regular, false, null);
    }

    private Form form(String id, String name, Field... fields) {
        return new Form(id, name, "description", List.of(fields));
    }

    @Test
    void roundTripsAForm() {
        repository.save(form("f1", "Contact", field("name", "Name")));

        var found = repository.findById("f1").orElseThrow();
        assertThat(found.name()).isEqualTo("Contact");
        assertThat(found.fields()).extracting(Field::id).containsExactly("name");
    }

    @Test
    void keepsFieldsOfDifferentFormsApartWhenTheyShareAnId() {
        repository.save(form("f1", "Approval A", field("comment", "Comment on A")));
        repository.save(form("f2", "Approval B", field("comment", "Comment on B")));

        assertThat(repository.findById("f1").orElseThrow().fields())
                .extracting(Field::label).containsExactly("Comment on A");
        assertThat(repository.findById("f2").orElseThrow().fields())
                .extracting(Field::label).containsExactly("Comment on B");
    }

    @Test
    void preservesFieldOrder() {
        repository.save(form("f1", "Contact",
                field("zulu", "Zulu"), field("alpha", "Alpha"), field("mike", "Mike")));

        assertThat(repository.findById("f1").orElseThrow().fields())
                .extracting(Field::id).containsExactly("zulu", "alpha", "mike");
    }

    @Test
    void dropsFieldsRemovedFromTheDefinition() {
        repository.save(form("f1", "Contact", field("name", "Name"), field("phone", "Phone")));
        repository.save(form("f1", "Contact", field("name", "Name")));

        assertThat(repository.findById("f1").orElseThrow().fields())
                .extracting(Field::id).containsExactly("name");
    }

    @Test
    void deletingAFormDeletesItsFields() {
        repository.save(form("f1", "Contact", field("name", "Name")));

        repository.deleteAllById(List.of("f1"));

        assertThat(repository.findById("f1")).isEmpty();
        assertThat(fieldEntityRepository.findByFormIdOrderByFieldOrderAsc("f1")).isEmpty();
    }

    @Test
    void defaultsAMissingStereotypeToRegular() {
        // Every write path can produce one: form-schema.json makes stereotype optional, so a
        // git-imported definition that omits it deserialises to null.
        repository.save(form("f1", "Contact",
                new Field("name", "Name", FieldDataType.string, null, false, null)));

        assertThat(repository.findById("f1").orElseThrow().fields())
                .extracting(Field::stereotype).containsExactly(FieldStereotype.regular);
    }

    @Test
    void rejectsAFormWithNoFields() {
        assertThatThrownBy(() -> repository.save(form("f1", "Contact")))
                .isInstanceOf(FormValidator.FormValidationException.class);
    }

    @Test
    void roundTripsTheOptionsAFieldOffers() {
        var decision = new Field("decision", "Decision", FieldDataType.string, FieldStereotype.radio,
                true, null, List.of(
                        new io.mateu.workflow.domain.FieldOption("WALK", "Walk the guest"),
                        new io.mateu.workflow.domain.FieldOption("REFUND", "Refund the guest"),
                        new io.mateu.workflow.domain.FieldOption("REJECT")));

        repository.save(form("f1", "Overbooking", decision));

        var options = repository.findById("f1").orElseThrow().fields().get(0).options();
        // Order is the order the definition declares — it is the order the choices are shown in.
        assertThat(options).extracting(io.mateu.workflow.domain.FieldOption::value)
                .containsExactly("WALK", "REFUND", "REJECT");
        assertThat(options).extracting(io.mateu.workflow.domain.FieldOption::label)
                .containsExactly("Walk the guest", "Refund the guest", "REJECT");
    }

    @Test
    void aFieldThatOffersNoOptionsStoresNoneRatherThanAnEmptyList() {
        repository.save(form("f1", "Contact", field("name", "Name")));

        assertThat(fieldEntityRepository.findByFormIdOrderByFieldOrderAsc("f1").get(0).getOptions())
                .as("no \"[]\" rows for every free-input field ever written")
                .isNull();
        assertThat(repository.findById("f1").orElseThrow().fields().get(0).options()).isEmpty();
    }

    @Test
    void unreadableStoredOptionsLeaveTheFieldWithNoneRatherThanFailingTheRead() {
        repository.save(form("f1", "Contact", field("name", "Name")));
        var stored = fieldEntityRepository.findByFormIdOrderByFieldOrderAsc("f1").get(0);
        stored.setOptions("not json");
        fieldEntityRepository.save(stored);

        assertThat(repository.findById("f1").orElseThrow().fields().get(0).options()).isEmpty();
    }

    @Test
    void roundTripsWhereAFieldFetchesItsChoicesFrom() {
        var country = new Field("country", "Country", FieldDataType.string, FieldStereotype.select,
                true, null, List.of(), new io.mateu.workflow.domain.FieldOptionsSource(
                        "https://restcountries.com/v3.1/all?fields=cca2,name", "cca2", "name.common"));

        repository.save(form("f1", "Booking", country));

        var source = repository.findById("f1").orElseThrow().fields().get(0).optionsSource();
        assertThat(source.url()).isEqualTo("https://restcountries.com/v3.1/all?fields=cca2,name");
        assertThat(source.valuePath()).isEqualTo("cca2");
        assertThat(source.labelPath()).isEqualTo("name.common");
        // Defaults survive the round trip rather than coming back null.
        assertThat(source.method()).isEqualTo("GET");
        assertThat(source.itemsPath()).isEmpty();
    }

    @Test
    void aFieldWithoutASourceStoresNone() {
        repository.save(form("f1", "Contact", field("name", "Name")));

        assertThat(fieldEntityRepository.findByFormIdOrderByFieldOrderAsc("f1").get(0).getOptionsSource()).isNull();
        assertThat(repository.findById("f1").orElseThrow().fields().get(0).optionsSource()).isNull();
    }
}
