package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.security.PasswordHasher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Règles métier de gestion des comptes PlugAdmin (issue #50) au-dessus d'un {@link UserRepository}
 * de stockage brut : validation d'entrée, hachage du mot de passe, et surtout les
 * <strong>garde-fous anti-verrouillage</strong> — on ne peut jamais retirer le rôle {@code OWNER}
 * au dernier OWNER actif, ni le désactiver, ni se désactiver soi-même.
 *
 * <p>Aucune de ces méthodes ne journalise ni ne rend un mot de passe / hash. L'audit est fait par
 * l'appelant (handler) avec les {@link Outcome#before()} / {@link Outcome#after()} renvoyés.</p>
 */
public final class UserDirectory {

    /** 3 à 32 caractères, commence par une lettre ou un chiffre, puis lettres / chiffres / {@code . _ -}. */
    public static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,31}");
    public static final int PASSWORD_MIN = 12;
    public static final int PASSWORD_MAX = 200;

    private final UserRepository repo;
    private final PasswordHasher hasher;

    public UserDirectory(UserRepository repo, PasswordHasher hasher) {
        this.repo = repo;
        this.hasher = hasher;
    }

    // ---- Lecture ----------------------------------------------------------------------

    public List<PanelUser> list() {
        return repo.all();
    }

    public Optional<PanelUser> byId(String id) {
        return repo.findById(id);
    }

    public Optional<PanelUser> byUsername(String username) {
        return repo.findByUsername(username);
    }

    public int activeOwnerCount() {
        return repo.countActiveOwners();
    }

    // ---- Amorçage du compte OWNER ---------------------------------------------------

    /**
     * Garantit qu'un compte {@code OWNER} correspondant aux identifiants d'environnement
     * ({@code RPGQUEST_PANEL_OWNER_USERNAME} / {@code RPGQUEST_PANEL_OWNER_HASH}) existe, est actif
     * et {@code OWNER} — c'est le <strong>chemin de récupération</strong> : tant que ces variables
     * sont définies, l'accès au panel ne peut pas être perdu, même si un OWNER a été désactivé via
     * l'interface. Le hash est réaligné sur celui de l'environnement à chaque démarrage (la V1 n'a
     * pas de changement de mot de passe en interface).
     */
    public void ensureBootstrapOwner(String username, String passwordHash) {
        if (username == null || username.isBlank() || passwordHash == null || passwordHash.isBlank()) {
            return;
        }
        Optional<PanelUser> existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            repo.insert(new PanelUser(UUID.randomUUID().toString(), username.trim(), passwordHash,
                    Role.OWNER, true, Instant.now(), null));
            return;
        }
        PanelUser u = existing.get();
        if (u.role() != Role.OWNER) {
            repo.updateRole(u.id(), Role.OWNER);
        }
        if (!u.active()) {
            repo.updateActive(u.id(), true);
        }
        if (!passwordHash.equals(u.passwordHash())) {
            repo.updatePasswordHash(u.id(), passwordHash);
        }
    }

    // ---- Mutations ----------------------------------------------------------------------

    public Outcome create(String username, String rawPassword, Role role) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            return Outcome.fail("Nom d'utilisateur invalide : 3 à 32 caractères, lettres, chiffres, « . _ - »,"
                    + " commençant par une lettre ou un chiffre.");
        }
        if (role == null) {
            return Outcome.fail("Rôle manquant ou inconnu.");
        }
        String pw = rawPassword == null ? "" : rawPassword;
        if (pw.length() < PASSWORD_MIN) {
            return Outcome.fail("Mot de passe trop court (au moins " + PASSWORD_MIN + " caractères).");
        }
        if (pw.length() > PASSWORD_MAX) {
            return Outcome.fail("Mot de passe trop long (au plus " + PASSWORD_MAX + " caractères).");
        }
        if (repo.findByUsername(name).isPresent()) {
            return Outcome.fail("Ce nom d'utilisateur est déjà pris.");
        }
        PanelUser user = new PanelUser(UUID.randomUUID().toString(), name, hasher.hash(pw),
                role, true, Instant.now(), null);
        try {
            repo.insert(user);
        } catch (UserRepository.DuplicateUsernameException e) {
            return Outcome.fail("Ce nom d'utilisateur est déjà pris.");
        }
        return Outcome.ok(null, user);
    }

    public Outcome changeRole(String targetId, Role newRole) {
        Optional<PanelUser> maybe = repo.findById(targetId);
        if (maybe.isEmpty()) {
            return Outcome.fail("Compte introuvable.");
        }
        if (newRole == null) {
            return Outcome.fail("Rôle manquant ou inconnu.");
        }
        PanelUser before = maybe.get();
        if (before.role() == newRole) {
            return Outcome.ok(before, before);
        }
        if (before.isOwner() && before.active() && newRole != Role.OWNER && repo.countActiveOwners() <= 1) {
            return Outcome.fail("Impossible : c'est le dernier OWNER actif. Promouvoir un autre OWNER d'abord.");
        }
        repo.updateRole(targetId, newRole);
        return Outcome.ok(before, repo.findById(targetId).orElse(before));
    }

    public Outcome setActive(String targetId, boolean active, String actingUserId) {
        Optional<PanelUser> maybe = repo.findById(targetId);
        if (maybe.isEmpty()) {
            return Outcome.fail("Compte introuvable.");
        }
        PanelUser before = maybe.get();
        if (before.active() == active) {
            return Outcome.ok(before, before);
        }
        if (!active) {
            if (targetId.equals(actingUserId)) {
                return Outcome.fail("Vous ne pouvez pas désactiver votre propre compte.");
            }
            if (before.isOwner() && repo.countActiveOwners() <= 1) {
                return Outcome.fail("Impossible : c'est le dernier OWNER actif.");
            }
        }
        repo.updateActive(targetId, active);
        return Outcome.ok(before, repo.findById(targetId).orElse(before));
    }

    /** Résultat d'une mutation : succès (avec état avant / après) ou échec (message humain). */
    public record Outcome(boolean ok, String error, PanelUser before, PanelUser after) {
        static Outcome ok(PanelUser before, PanelUser after) {
            return new Outcome(true, null, before, after);
        }

        static Outcome fail(String error) {
            return new Outcome(false, error, null, null);
        }
    }
}
