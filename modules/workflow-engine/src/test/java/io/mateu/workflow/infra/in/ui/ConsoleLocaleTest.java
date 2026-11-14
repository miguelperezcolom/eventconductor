package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Translator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The console's language: English unless configured, the application's own choice when it has a translator. */
class ConsoleLocaleTest {

    private final HttpRequest spanishBrowser = mock(HttpRequest.class);

    {
        when(spanishBrowser.getHeaderValue("Accept-Language")).thenReturn("es-ES,es;q=0.9");
    }

    private static ConsoleLocale consoleLocale(MockEnvironment environment, Translator... others) {
        var beans = new DefaultListableBeanFactory();
        for (int i = 0; i < others.length; i++) {
            beans.registerSingleton("translator" + i, others[i]);
        }
        var locale = new ConsoleLocale(beans.getBeanProvider(Translator.class), environment);
        beans.registerSingleton("consoleLocale", locale);
        return locale;
    }

    @Test
    void theConsoleIsEnglishWhateverTheBrowserAsks() {
        var locale = consoleLocale(new MockEnvironment(), new io.mateu.core.infra.DefaultTranslator());

        assertThat(locale.locale(spanishBrowser)).isEqualTo("en");
        assertThat(locale.translate("Retry all matching", spanishBrowser)).isEqualTo("Retry all matching");
    }

    @Test
    void theLanguageCanBeSetOrHandedBackToTheBrowser() {
        assertThat(consoleLocale(new MockEnvironment().withProperty(ConsoleLocale.PROPERTY, "fr"))
                .locale(spanishBrowser)).isEqualTo("fr");
        assertThat(consoleLocale(new MockEnvironment().withProperty(ConsoleLocale.PROPERTY, "auto"),
                new io.mateu.core.infra.DefaultTranslator()).locale(spanishBrowser)).startsWith("es");
    }

    @Test
    void anApplicationsOwnTranslatorKeepsDecidingAndTranslating() {
        var own = new Translator() {
            @Override
            public String translate(String text, HttpRequest httpRequest) {
                return "[" + text + "]";
            }

            @Override
            public String locale(HttpRequest httpRequest) {
                return "de";
            }
        };
        var locale = consoleLocale(new MockEnvironment(), new io.mateu.core.infra.DefaultTranslator(), own);

        assertThat(locale.locale(spanishBrowser)).isEqualTo("de");
        assertThat(locale.translate("New", spanishBrowser)).isEqualTo("[New]");
    }

    @Test
    void withoutAnyTranslatorBeanItStillTranslatesThroughMateusDefault() {
        var locale = consoleLocale(new MockEnvironment());

        assertThat(locale.translate("New", spanishBrowser)).isEqualTo("New");
        assertThat(locale.locale(spanishBrowser)).isEqualTo("en");
    }
}
