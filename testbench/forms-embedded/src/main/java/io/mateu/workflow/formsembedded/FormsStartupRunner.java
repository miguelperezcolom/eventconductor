package io.mateu.workflow.formsembedded;

import io.mateu.workflow.application.out.FormRepository;
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
        formRepository.save(new Form(
                UUID.randomUUID().toString(),
                "Contact Form",
                "A simple contact form example",
                List.of()
        ));
    }

}
