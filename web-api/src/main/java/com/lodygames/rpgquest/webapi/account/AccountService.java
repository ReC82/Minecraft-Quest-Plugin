package com.lodygames.rpgquest.webapi.account;

import com.lodygames.rpgquest.webapi.json.Json;
import com.lodygames.rpgquest.webapi.privacy.AgePolicy;
import com.lodygames.rpgquest.webapi.privacy.ParentalConsentStatus;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Comptes joueurs du portail et liaison au compte Minecraft (issue #53).
 *
 * <p>Toute la logique de sécurité vit ici, sans HTTP : création de compte soumise à la politique
 * d'âge (#55), connexion à mot de passe haché avec limitation des échecs, sessions à jeton aléatoire,
 * défis de liaison à usage unique, limitation des preuves ratées par joueur.</p>
 *
 * <p>Ce service ne donne <strong>aucun</strong> droit d'administration : ni PlugAdmin, ni jeu. Il
 * n'écrit jamais dans les données de gameplay.</p>
 */
public final class AccountService {

    public static final int MAX_CHALLENGES_PER_HOUR = 5;
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
    static final int MIN_PASSWORD_LENGTH = 10;
    private static final int MAX_PASSWORD_LENGTH = 128;

    public enum RegistrationOutcome {
        CREATED,
        /** Âge sous le seuil : compte créé, mais limité jusqu'au consentement parental. */
        CREATED_RESTRICTED,
        CLOSED,
        REFUSED_BY_AGE_POLICY,
        INVALID_LOGIN,
        WEAK_PASSWORD,
        LOGIN_TAKEN,
        /** Trop d'inscriptions récentes depuis cette adresse. */
        TOO_MANY
    }

    public enum ChallengeOutcome {
        CREATED,
        ALREADY_LINKED,
        RESTRICTED,
        TOO_MANY
    }

    public record Challenge(ChallengeOutcome outcome, String code, Instant expiresAt) {
    }

    /** Jeton brut à poser dans le cookie ; seule son empreinte est stockée. */
    public record NewSession(String token, String csrfToken, Instant expiresAt) {
    }

    public record Overview(AccountStore.Account account, Optional<AccountStore.Link> link,
            List<AccountStore.AuditEntry> history, Optional<PersonalProgress> progress) {
    }

    /** Progression du joueur relié, telle que reçue du serveur de jeu, et quand elle est arrivée. */
    public record PersonalProgress(LinkedProgress progress, Instant receivedAt) {
    }

    /** Écart minimal entre deux enregistrements de progression d'un même joueur. */
    static final Duration PROGRESS_MIN_INTERVAL = Duration.ofSeconds(30);

    private final AccountStore store;
    private final PasswordHasher hasher;
    private final AgePolicy agePolicy;
    private final AccountSettings settings;
    private final Clock clock;
    private final AttemptLimiter loginFailures;
    private final AttemptLimiter linkFailures;
    /** Chaque inscription coûte un hachage : bornée par adresse, réussie ou non. */
    private final AttemptLimiter registrations;
    /** Haché une fois : la vérification d'un identifiant inconnu coûte autant qu'un vrai. */
    private final String decoyHash;

    public AccountService(AccountStore store, PasswordHasher hasher, AgePolicy agePolicy, AccountSettings settings,
            Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.agePolicy = agePolicy;
        this.settings = settings;
        this.clock = clock;
        this.loginFailures = new AttemptLimiter(10, Duration.ofMinutes(15), clock);
        this.linkFailures = new AttemptLimiter(5, Duration.ofMinutes(15), clock);
        this.registrations = new AttemptLimiter(5, Duration.ofHours(1), clock);
        this.decoyHash = hasher.hash(SecureTokens.newToken());
    }

    /** Comptes réellement ouverts : activés par le propriétaire <em>et</em> politique d'âge configurée. */
    public boolean open() {
        return settings.enabled() && agePolicy.accountsOpen();
    }

    public AgePolicy agePolicy() {
        return agePolicy;
    }

    public RegistrationOutcome register(String login, String password, Optional<String> region, int declaredAge,
            String clientKey) throws SQLException {
        if (!open()) {
            return RegistrationOutcome.CLOSED;
        }
        if (registrations.isBlocked(clientKey)) {
            return RegistrationOutcome.TOO_MANY;
        }
        if (login == null || !LOGIN.matcher(login).matches()) {
            return RegistrationOutcome.INVALID_LOGIN;
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH
                || password.equalsIgnoreCase(login)) {
            return RegistrationOutcome.WEAK_PASSWORD;
        }
        AgePolicy.Outcome decision = agePolicy.evaluate(region, declaredAge);
        Optional<ParentalConsentStatus> consent = AgePolicy.initialConsent(decision);
        if (consent.isEmpty()) {
            // Rien n'est conservé d'une demande refusée.
            return RegistrationOutcome.REFUSED_BY_AGE_POLICY;
        }
        registrations.recordFailure(clientKey);
        AccountStore.Account account = new AccountStore.Account(UUID.randomUUID().toString(), login,
                hasher.hash(password), consent.get(), clock.instant());
        if (!store.createAccount(account)) {
            return RegistrationOutcome.LOGIN_TAKEN;
        }
        return consent.get().allowsPersonalFeatures()
                ? RegistrationOutcome.CREATED
                : RegistrationOutcome.CREATED_RESTRICTED;
    }

    /**
     * Vide si l'identifiant ou le mot de passe est faux, ou si l'IP / l'identifiant a trop échoué ;
     * le visiteur ne sait jamais lequel.
     */
    public Optional<NewSession> login(String login, String password, String clientKey) throws SQLException {
        if (!open() || login == null || password == null || password.length() > MAX_PASSWORD_LENGTH) {
            return Optional.empty();
        }
        String loginKey = "login:" + login.toLowerCase(java.util.Locale.ROOT);
        String ipKey = "ip:" + clientKey;
        if (loginFailures.isBlocked(loginKey) || loginFailures.isBlocked(ipKey)) {
            return Optional.empty();
        }
        Optional<AccountStore.Account> account = store.findAccountByLogin(login);
        boolean valid = hasher.verify(password, account.map(AccountStore.Account::passwordHash).orElse(decoyHash))
                && account.isPresent();
        if (!valid) {
            loginFailures.recordFailure(loginKey);
            loginFailures.recordFailure(ipKey);
            return Optional.empty();
        }
        return Optional.of(openSession(account.get().id()));
    }

    /** Session neuve après connexion : jamais de réutilisation d'un jeton antérieur (fixation). */
    private NewSession openSession(String accountId) throws SQLException {
        String token = SecureTokens.newToken();
        String csrf = SecureTokens.newToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(settings.sessionTtl());
        store.createSession(SecureTokens.sha256(token), accountId, csrf, now, expiresAt);
        return new NewSession(token, csrf, expiresAt);
    }

    public Optional<AccountStore.Session> session(String token) throws SQLException {
        if (token == null || token.isBlank() || !settings.enabled()) {
            return Optional.empty();
        }
        return store.findSession(SecureTokens.sha256(token), clock.instant());
    }

    public void logout(String token) throws SQLException {
        if (token != null && !token.isBlank()) {
            store.deleteSession(SecureTokens.sha256(token));
        }
    }

    public Overview overview(String accountId) throws SQLException {
        AccountStore.Account account = store.findAccount(accountId)
                .orElseThrow(() -> new IllegalStateException("Session sans compte"));
        Optional<AccountStore.Link> link = store.findLink(accountId);
        Optional<PersonalProgress> progress = Optional.empty();
        if (link.isPresent()) {
            progress = store.findProgress(accountId).map(stored -> new PersonalProgress(
                    LinkedProgress.fromWire(Json.parse(stored.payload())),
                    stored.receivedAt()));
        }
        return new Overview(account, link, store.auditOf(accountId, 10), progress);
    }

    public Challenge createChallenge(String accountId) throws SQLException {
        Optional<AccountStore.Account> account = store.findAccount(accountId);
        if (account.isEmpty() || !account.get().consent().allowsPersonalFeatures()) {
            return new Challenge(ChallengeOutcome.RESTRICTED, null, null);
        }
        if (store.findLink(accountId).isPresent()) {
            return new Challenge(ChallengeOutcome.ALREADY_LINKED, null, null);
        }
        Instant now = clock.instant();
        if (store.countChallengesSince(accountId, now.minus(Duration.ofHours(1))) >= MAX_CHALLENGES_PER_HOUR) {
            return new Challenge(ChallengeOutcome.TOO_MANY, null, null);
        }
        String code = SecureTokens.newLinkCode();
        Instant expiresAt = now.plus(settings.linkCodeTtl());
        store.replaceChallenge(accountId, SecureTokens.sha256(code), now, expiresAt);
        return new Challenge(ChallengeOutcome.CREATED, code, expiresAt);
    }

    /**
     * Preuve venue du serveur Minecraft : {@code playerUuid} est celui du joueur qui a réellement
     * tapé la commande, jamais une valeur saisie.
     */
    public LinkOutcome confirmLink(String rawCode, UUID playerUuid, String playerName) throws SQLException {
        String failureKey = playerUuid.toString();
        if (!settings.enabled()) {
            return LinkOutcome.INVALID_CODE;
        }
        if (linkFailures.isBlocked(failureKey)) {
            return LinkOutcome.TOO_MANY_ATTEMPTS;
        }
        String code = SecureTokens.normalizeCode(rawCode);
        LinkOutcome outcome = code.isEmpty()
                ? LinkOutcome.INVALID_CODE
                : store.consumeChallenge(SecureTokens.sha256(code), playerUuid, playerName, clock.instant());
        if (outcome == LinkOutcome.INVALID_CODE) {
            linkFailures.recordFailure(failureKey);
        }
        return outcome;
    }

    /** Sonde du serveur de jeu (#53) : cet UUID est-il relié ? Rien n'est écrit ni journalisé. */
    public boolean isLinked(UUID playerUuid) throws SQLException {
        return settings.enabled() && store.isLinked(playerUuid);
    }

    /**
     * Progression envoyée par le serveur de jeu pour {@code playerUuid} (#53) : conservée seulement si
     * cet UUID est relié, et effacée avec la liaison.
     */
    public AccountStore.ProgressWrite recordProgress(UUID playerUuid, LinkedProgress progress) throws SQLException {
        if (!settings.enabled()) {
            return AccountStore.ProgressWrite.NOT_LINKED;
        }
        Instant now = clock.instant();
        // Une date future (horloge décalée, envoi forgé) ne doit pas bloquer les envois suivants.
        Instant generatedAt = progress.generatedAt().isAfter(now) ? now : progress.generatedAt();
        return store.saveProgress(playerUuid, Json.write(progress.toWire()), generatedAt, now, PROGRESS_MIN_INTERVAL);
    }

    /** Dissociation : exige le mot de passe, comme toute action qui retire une protection. */
    public boolean unlink(String accountId, String password) throws SQLException {
        Optional<AccountStore.Account> account = store.findAccount(accountId);
        if (account.isEmpty() || !hasher.verify(password, account.get().passwordHash())) {
            return false;
        }
        return store.unlink(accountId, clock.instant());
    }
}
