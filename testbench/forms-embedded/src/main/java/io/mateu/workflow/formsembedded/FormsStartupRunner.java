package io.mateu.workflow.formsembedded;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FormsStartupRunner implements ApplicationRunner {

    final FormRepository formRepository;

    @Override
    public void run(ApplicationArguments args) {
        // Something to open in the editor. The schema requires at least one field, so seed a couple.
        formRepository.save(new Form(
                UUID.randomUUID().toString(),
                "Contact Form",
                "A simple contact form example",
                List.of(
                        new Field("email", "Email", FieldDataType.string, FieldStereotype.regular, true, "Your email address"),
                        new Field("message", "Message", FieldDataType.string, FieldStereotype.textarea, true, "How can we help?")
                )
        ));
    }

}
