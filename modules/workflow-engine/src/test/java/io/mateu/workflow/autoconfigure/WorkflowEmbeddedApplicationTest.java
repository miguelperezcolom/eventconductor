package io.mateu.workflow.autoconfigure;

import com.example.myapp.MyCustomUserBean;
import com.example.myapp.MyTestApp;
import io.mateu.workflow.application.services.CommandDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEmbeddedApplicationTest {

    @Test
    void whenAppIsOutsideMateuPackage_scansBothFrameworkAndUserPackage() {
        new ApplicationContextRunner()
                .withUserConfiguration(MyTestApp.class)
                .run(context -> {
                    // Framework bean should be loaded (from package io.mateu.workflow)
                    assertThat(context).hasSingleBean(CommandDispatcher.class);

                    // User bean should be loaded (from package com.example.myapp)
                    assertThat(context).hasSingleBean(MyCustomUserBean.class);
                });
    }
}
