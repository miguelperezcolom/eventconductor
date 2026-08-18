package io.mateu.testworker;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The root the slice tests look upwards for. It starts nothing on its own: {@code @DataJpaTest}
 * excludes ordinary components, so this only tells Spring where the entities and repositories are.
 */
@SpringBootApplication
public class TestApplication {
}
