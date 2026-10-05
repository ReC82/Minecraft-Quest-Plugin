package com.lodygames.rpgquest.ops;

import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.slf4j.Logger;

/**
 * Branche {@link ServerLogBuffer} sur la console réelle du serveur (issue #95).
 *
 * <p><strong>Pourquoi Log4j et pas {@code java.util.logging}.</strong> Un {@code Handler} JUL
 * posé sur {@code Bukkit.getLogger()} ne capterait que les plugins qui journalisent <em>via JUL</em>.
 * Or RPGQuest utilise {@code getSLF4JLogger()}, que Paper route directement vers Log4j2 : la
 * quasi-totalité de nos propres lignes échapperait au tampon, et la console serait vide des seules
 * lignes qui nous intéressent. Log4j2 est la sortie réelle — vanilla, Citizens, WorldEdit,
 * Multiverse et RPGQuest y passent tous.</p>
 *
 * <p><strong>Ce n'est ni du NMS ni de la réflexion CraftBukkit</strong> : Log4j2 est une
 * bibliothèque de journalisation tierce, déclarée {@code compileOnly} (fournie par le serveur) et
 * jamais empaquetée. Si elle est absente ou incompatible, {@link #install} renvoie une raison
 * lisible et le plugin continue <strong>normalement</strong> : la console du panel s'affiche alors
 * « indisponible » avec ce motif, au lieu de faire tomber le serveur pour un confort
 * d'administration.</p>
 *
 * <p>Lecture seule et sans effet : l'appender n'altère, ne filtre ni ne consomme aucun événement —
 * il en prend une copie textuelle bornée. Il ne journalise jamais rien lui-même (ce serait une
 * récursion immédiate).</p>
 */
public final class ConsoleTap {

    /** Nom de l'appender : unique, et reconnaissable dans un diagnostic Log4j. */
    static final String APPENDER_NAME = "RPGQuestOpsConsole";

    private final ServerLogBuffer buffer;
    private Appender appender;
    private LoggerContext context;

    public ConsoleTap(ServerLogBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Attache l'appender à la racine Log4j2.
     *
     * @return {@link Optional#empty()} si tout va bien, sinon la <strong>raison</strong> de
     *     l'indisponibilité, destinée à être affichée telle quelle dans le panel.
     */
    public synchronized Optional<String> install(Logger log) {
        if (appender != null) {
            return Optional.empty(); // déjà installé : idempotent
        }
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            AbstractAppender tap = new AbstractAppender(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    capture(event);
                }
            };
            tap.start();
            config.addAppender(tap);
            // Niveau INFO : on ne veut pas du DEBUG de tous les plugins dans un tampon de 500 lignes.
            // Ce seuil s'applique à NOTRE appender seul — le niveau propre des loggers du serveur
            // n'est jamais modifié : capter la console ne doit pas changer ce que le serveur
            // journalise.
            config.getRootLogger().addAppender(tap, Level.INFO, null);
            ctx.updateLoggers();
            this.appender = tap;
            this.context = ctx;
            log.info("[ops] Console serveur captée pour PlugAdmin ({} lignes conservées).", buffer.capacity());

            // Le niveau du logger racine filtre AVANT tout appender. Si le serveur journalise
            // au-dessus de INFO, la console du panel paraîtrait vide sans raison visible : on le
            // dit, au lieu de laisser chercher une panne qui n'existe pas.
            Level rootLevel = config.getRootLogger().getLevel();
            if (rootLevel != null && !rootLevel.isLessSpecificThan(Level.INFO)) {
                String restricted = "Le serveur journalise au niveau " + rootLevel.name()
                        + " : seules les lignes de ce niveau ou plus graves apparaissent ici.";
                log.warn("[ops] {}", restricted);
                return Optional.of(restricted);
            }
            return Optional.empty();
        } catch (LinkageError | RuntimeException e) {
            // Volontairement large : une version de Log4j différente se manifeste par une erreur de
            // liaison, pas par une exception. Le serveur doit démarrer quand même.
            String reason = "Capture de la console indisponible sur ce serveur ("
                    + e.getClass().getSimpleName() + ") — la journalisation du serveur n'est pas affectée.";
            log.warn("[ops] {}", reason);
            return Optional.of(reason);
        }
    }

    /** Détache l'appender. Idempotent, et silencieux sur une pile déjà démontée. */
    public synchronized void uninstall() {
        Appender current = appender;
        LoggerContext ctx = context;
        appender = null;
        context = null;
        if (current == null) {
            return;
        }
        try {
            if (ctx != null) {
                ctx.getConfiguration().getRootLogger().removeAppender(APPENDER_NAME);
                ctx.updateLoggers();
            }
            current.stop();
        } catch (LinkageError | RuntimeException ignored) {
            // Arrêt du serveur : plus rien à sauver, et surtout rien à journaliser ici.
        }
    }

    /** Recopie bornée d'un événement. Ne journalise rien : ce serait une récursion. */
    private void capture(LogEvent event) {
        try {
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            if (event.getThrown() != null) {
                message = message + " | " + event.getThrown().getClass().getSimpleName();
            }
            String level = event.getLevel() == null ? "INFO" : event.getLevel().name();
            String source = event.getLoggerName() == null || event.getLoggerName().isBlank()
                    ? "server" : event.getLoggerName();
            buffer.append(event.getTimeMillis(), level, source, message);
        } catch (RuntimeException ignored) {
            // Une ligne perdue ne doit jamais perturber la journalisation du serveur.
        }
    }
}
