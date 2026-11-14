package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Translator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * The language the console is drawn in: English by default, whatever the browser asks for.
 *
 * <p>Every word the engine's pages write — titles, columns, actions, help — is English. Mateu draws the
 * rest of the screen (the "New" button, the search box's placeholder, confirmations, toasts) in the
 * language of the request, from {@code Accept-Language}, through its {@link Translator}. On a browser
 * set to Spanish that gave a console in two languages: "Nuevo" beside "Retry all matching", "Buscar"
 * above an English grid. This {@code Translator} answers the console's language instead
 * ({@value #PROPERTY}, default {@code en}), so Mateu's own words match the engine's.
 *
 * <p>It only decides the <em>language</em>. Translating is left to the application's own
 * {@code Translator} when it has one, otherwise to Mateu's default — both keep working unchanged. And an
 * application that brings its own {@code Translator} keeps choosing the language as well: it is the one
 * that knows what its pages are written in. {@code workflow.ui.locale=auto} hands the choice back to the
 * browser, as before.
 *
 * <p>{@link Primary} because Mateu asks for one {@code Translator} and its default is already a bean.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Primary
public class ConsoleLocale implements Translator {

    /** The console's language, a BCP 47 tag; {@code auto} follows the browser. */
    public static final String PROPERTY = "workflow.ui.locale";
    static final String DEFAULT_LOCALE = "en";
    static final String AUTO = "auto";
    /** Mateu's own translator: it reads the language from {@code Accept-Language}. */
    static final String MATEU_DEFAULT = "io.mateu.core.infra.DefaultTranslator";

    private final ObjectProvider<Translator> translators;
    private final Environment environment;
    private volatile Translator fallback;

    public ConsoleLocale(ObjectProvider<Translator> translators, Environment environment) {
        this.translators = translators;
        this.environment = environment;
    }

    @Override
    public String translate(String text, HttpRequest httpRequest) {
        var delegate = delegate();
        return delegate == null ? text : delegate.translate(text, httpRequest);
    }

    @Override
    public String locale(HttpRequest httpRequest) {
        var own = applicationTranslator();
        if (own != null) {
            return own.locale(httpRequest);
        }
        var configured = configuredLocale();
        if (configured == null) {
            var delegate = delegate();
            return delegate == null ? null : delegate.locale(httpRequest);
        }
        return configured;
    }

    /** The configured language; null for {@code auto}. */
    String configuredLocale() {
        var value = environment.getProperty(PROPERTY, DEFAULT_LOCALE);
        if (value == null || value.isBlank() || AUTO.equalsIgnoreCase(value.strip())) {
            return null;
        }
        return value.strip();
    }

    /** A {@code Translator} the application declared itself — not this one, not Mateu's default. */
    Translator applicationTranslator() {
        return translators.stream()
                .filter(translator -> translator != this && !isMateuDefault(translator))
                .findFirst()
                .orElse(null);
    }

    /** Who translates: the application's translator, Mateu's default bean, or a default of our own. */
    Translator delegate() {
        var own = applicationTranslator();
        if (own != null) {
            return own;
        }
        var mateu = translators.stream()
                .filter(translator -> translator != this && isMateuDefault(translator))
                .findFirst()
                .orElse(null);
        if (mateu != null) {
            return mateu;
        }
        if (fallback == null) {
            fallback = new io.mateu.core.infra.DefaultTranslator();
        }
        return fallback;
    }

    private static boolean isMateuDefault(Translator translator) {
        return org.springframework.util.ClassUtils.getUserClass(translator).getName().equals(MATEU_DEFAULT);
    }
}
