package com.lodygames.rpgquest.webapi.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.account.AccountService.ChallengeOutcome;
import com.lodygames.rpgquest.webapi.account.AccountService.RegistrationOutcome;
import com.lodygames.rpgquest.webapi.privacy.AgePolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #53 : comptes, sessions et liaison compte ↔ UUID, sans HTTP. */
class AccountServiceTest {

    private static final AgePolicy BELGIUM_13 = new AgePolicy(Map.of("BE", 13),
            AgePolicy.Outcome.PARENTAL_CONSENT_REQUIRED, AgePolicy.Outcome.REFUSED);
    private static final String IP = "203.0.113.7";
    private static final UUID ALICE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path tempDir;

    private MutableClock clock;
    private AccountStore store;
    private AccountService service;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-15T18:00:00Z"));
        store = new AccountStore(tempDir.resolve("accounts.db"));
        service = newService(BELGIUM_13, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private AccountService newService(AgePolicy policy, boolean enabled) {
        AccountSettings settings = new AccountSettings(enabled, tempDir.resolve("accounts.db"), Duration.ofDays(7),
                Duration.ofMinutes(10), "link-token", true);
        return new AccountService(store, new PasswordHasher(1_000), policy, settings, clock);
    }

    private String registerAndLogin(String login) throws Exception {
        assertEquals(RegistrationOutcome.CREATED, service.register(login, "motdepasse-" + login, Optional.of("BE"), 15, IP));
        return store.findAccountByLogin(login).orElseThrow().id();
    }

    private String newCode(String accountId) throws Exception {
        AccountService.Challenge challenge = service.createChallenge(accountId);
        assertEquals(ChallengeOutcome.CREATED, challenge.outcome());
        return challenge.code();
    }

    // --- inscription / connexion -----------------------------------------------------------

    @Test
    void registrationStaysClosedWithoutRegionOrWhenDisabled() throws Exception {
        assertEquals(RegistrationOutcome.CLOSED,
                newService(AgePolicy.closed(), true).register("alice", "motdepasse-alice", Optional.of("BE"), 20, IP));
        assertEquals(RegistrationOutcome.CLOSED,
                newService(BELGIUM_13, false).register("alice", "motdepasse-alice", Optional.of("BE"), 20, IP));
    }

    @Test
    void registrationValidatesLoginPasswordAndUniqueness() throws Exception {
        assertEquals(RegistrationOutcome.INVALID_LOGIN, service.register("a", "motdepasse-ok", Optional.of("BE"), 20, IP));
        assertEquals(RegistrationOutcome.INVALID_LOGIN, service.register("élise", "motdepasse-ok", Optional.of("BE"), 20, IP));
        assertEquals(RegistrationOutcome.WEAK_PASSWORD, service.register("alice", "court", Optional.of("BE"), 20, IP));
        assertEquals(RegistrationOutcome.WEAK_PASSWORD, service.register("alice1234567", "ALICE1234567", Optional.of("BE"), 20, IP));
        registerAndLogin("alice");
        assertEquals(RegistrationOutcome.LOGIN_TAKEN, service.register("ALICE", "motdepasse-2", Optional.of("BE"), 20, IP));
    }

    @Test
    void registrationsArePerAddressLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            service.register("compte" + i, "motdepasse-solide", Optional.of("BE"), 20, "198.51.100.1");
        }
        assertEquals(RegistrationOutcome.TOO_MANY,
                service.register("compte9", "motdepasse-solide", Optional.of("BE"), 20, "198.51.100.1"));
        assertEquals(RegistrationOutcome.CREATED,
                service.register("autre", "motdepasse-solide", Optional.of("BE"), 20, "198.51.100.2"));
        clock.advance(Duration.ofMinutes(61));
        assertEquals(RegistrationOutcome.CREATED,
                service.register("compte9", "motdepasse-solide", Optional.of("BE"), 20, "198.51.100.1"));
    }

    @Test
    void agePolicyDecidesAndNothingIsKeptFromARefusal() throws Exception {
        assertEquals(RegistrationOutcome.REFUSED_BY_AGE_POLICY,
                service.register("loin", "motdepasse-loin", Optional.of("US"), 30, IP));
        assertTrue(store.findAccountByLogin("loin").isEmpty());

        assertEquals(RegistrationOutcome.CREATED_RESTRICTED,
                service.register("petit", "motdepasse-petit", Optional.of("BE"), 11, IP));
        String petit = store.findAccountByLogin("petit").orElseThrow().id();
        assertEquals(ChallengeOutcome.RESTRICTED, service.createChallenge(petit).outcome());
    }

    @Test
    void loginOpensAFreshSessionAndLogoutEndsIt() throws Exception {
        registerAndLogin("alice");

        assertTrue(service.login("alice", "mauvais-mot", "1.2.3.4").isEmpty());
        AccountService.NewSession first = service.login("Alice", "motdepasse-alice", "1.2.3.4").orElseThrow();
        AccountService.NewSession second = service.login("alice", "motdepasse-alice", "1.2.3.4").orElseThrow();

        assertNotEquals(first.token(), second.token());
        assertNotEquals(first.token(), first.csrfToken());
        assertTrue(service.session(first.token()).isPresent());
        service.logout(first.token());
        assertTrue(service.session(first.token()).isEmpty());
        assertTrue(service.session(second.token()).isPresent());
    }

    @Test
    void sessionsExpire() throws Exception {
        registerAndLogin("alice");
        AccountService.NewSession session = service.login("alice", "motdepasse-alice", "ip").orElseThrow();

        clock.advance(Duration.ofDays(7).plusSeconds(1));

        assertTrue(service.session(session.token()).isEmpty());
    }

    @Test
    void repeatedLoginFailuresLockEvenTheRightPassword() throws Exception {
        registerAndLogin("alice");
        for (int i = 0; i < 10; i++) {
            assertTrue(service.login("alice", "essai-" + i, "9.9.9.9").isEmpty());
        }

        assertTrue(service.login("alice", "motdepasse-alice", "8.8.8.8").isEmpty(), "identifiant bloqué");
        clock.advance(Duration.ofMinutes(16));
        assertTrue(service.login("alice", "motdepasse-alice", "8.8.8.8").isPresent());
    }

    // --- liaison ---------------------------------------------------------------------------

    @Test
    void validCodeLinksTheUuidOfThePlayerWhoTypedIt() throws Exception {
        String alice = registerAndLogin("alice");
        String code = newCode(alice);

        assertEquals(LinkOutcome.LINKED, service.confirmLink(code.toLowerCase(), ALICE_UUID, "AliceMC"));

        AccountStore.Link link = store.findLink(alice).orElseThrow();
        assertEquals(ALICE_UUID, link.playerUuid());
        assertEquals("AliceMC", link.playerName());
        assertEquals("LINKED", store.auditOf(alice, 5).get(0).action());
    }

    @Test
    void usedCodeCannotBeReplayedByAnotherPlayer() throws Exception {
        String alice = registerAndLogin("alice");
        String code = newCode(alice);
        assertEquals(LinkOutcome.LINKED, service.confirmLink(code, ALICE_UUID, "AliceMC"));

        assertEquals(LinkOutcome.INVALID_CODE, service.confirmLink(code, BOB_UUID, "BobMC"));
        assertEquals(ALICE_UUID, store.findLink(alice).orElseThrow().playerUuid());
    }

    @Test
    void expiredCodeIsRefused() throws Exception {
        String alice = registerAndLogin("alice");
        String code = newCode(alice);

        clock.advance(Duration.ofMinutes(10));

        assertEquals(LinkOutcome.INVALID_CODE, service.confirmLink(code, ALICE_UUID, "AliceMC"));
        assertTrue(store.findLink(alice).isEmpty());
    }

    @Test
    void aNewCodeRevokesThePreviousOne() throws Exception {
        String alice = registerAndLogin("alice");
        String old = newCode(alice);
        String fresh = newCode(alice);

        assertEquals(LinkOutcome.INVALID_CODE, service.confirmLink(old, ALICE_UUID, "AliceMC"));
        assertEquals(LinkOutcome.LINKED, service.confirmLink(fresh, ALICE_UUID, "AliceMC"));
    }

    @Test
    void aUuidCanOnlyBeLinkedToOneAccount() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        service.confirmLink(newCode(alice), ALICE_UUID, "AliceMC");

        String bobCode = newCode(bob);
        assertEquals(LinkOutcome.UUID_ALREADY_LINKED, service.confirmLink(bobCode, ALICE_UUID, "AliceMC"));
        assertTrue(store.findLink(bob).isEmpty());
        assertEquals("REFUSED_UUID_TAKEN", store.auditOf(bob, 5).get(0).action());
        assertEquals(LinkOutcome.INVALID_CODE, service.confirmLink(bobCode, BOB_UUID, "BobMC"), "code consommé");
        assertEquals(ChallengeOutcome.ALREADY_LINKED, service.createChallenge(alice).outcome());
    }

    @Test
    void unlinkNeedsThePasswordIsAuditedAndFreesTheUuid() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        service.confirmLink(newCode(alice), ALICE_UUID, "AliceMC");

        assertFalse(service.unlink(alice, "mauvais"));
        assertTrue(store.findLink(alice).isPresent());
        assertTrue(service.unlink(alice, "motdepasse-alice"));
        assertTrue(store.findLink(alice).isEmpty());
        assertEquals("UNLINKED", store.auditOf(alice, 5).get(0).action());

        assertEquals(LinkOutcome.LINKED, service.confirmLink(newCode(bob), ALICE_UUID, "AliceMC"));
    }

    @Test
    void challengeCreationIsLimitedPerHour() throws Exception {
        String alice = registerAndLogin("alice");
        for (int i = 0; i < AccountService.MAX_CHALLENGES_PER_HOUR; i++) {
            newCode(alice);
        }
        assertEquals(ChallengeOutcome.TOO_MANY, service.createChallenge(alice).outcome());
        clock.advance(Duration.ofMinutes(61));
        assertEquals(ChallengeOutcome.CREATED, service.createChallenge(alice).outcome());
    }

    @Test
    void wrongCodesFromOnePlayerAreThrottled() throws Exception {
        String alice = registerAndLogin("alice");
        for (int i = 0; i < 5; i++) {
            assertEquals(LinkOutcome.INVALID_CODE, service.confirmLink("ZZZZZZZZ", BOB_UUID, "BobMC"));
        }
        String code = newCode(alice);
        assertEquals(LinkOutcome.TOO_MANY_ATTEMPTS, service.confirmLink(code, BOB_UUID, "BobMC"));
        assertEquals(LinkOutcome.LINKED, service.confirmLink(code, ALICE_UUID, "AliceMC"), "le code n'a pas été brûlé");
    }

    @Test
    void neitherCodesNorSessionTokensNorPasswordsAreStoredInClear() throws Exception {
        String alice = registerAndLogin("alice");
        String code = newCode(alice);
        AccountService.NewSession session = service.login("alice", "motdepasse-alice", "ip").orElseThrow();
        store.close();

        String raw = new String(Files.readAllBytes(tempDir.resolve("accounts.db")), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(code));
        assertFalse(raw.contains(session.token()));
        assertFalse(raw.contains("motdepasse-alice"));
        store = new AccountStore(tempDir.resolve("accounts.db"));
    }

    // --- gestion du compte (lot 7) ---------------------------------------------------------

    @Test
    void passwordChangeNeedsTheCurrentOneAndClosesOtherSessions() throws Exception {
        String alice = registerAndLogin("alice");
        AccountService.NewSession here = service.login("alice", "motdepasse-alice", "ip").orElseThrow();
        AccountService.NewSession elsewhere = service.login("alice", "motdepasse-alice", "ip2").orElseThrow();

        assertEquals(AccountService.PasswordChange.WRONG_PASSWORD,
                service.changePassword(alice, here.token(), "faux", "nouveau-secret-1"));
        assertEquals(AccountService.PasswordChange.WEAK_PASSWORD,
                service.changePassword(alice, here.token(), "motdepasse-alice", "court"));
        assertEquals(AccountService.PasswordChange.WEAK_PASSWORD,
                service.changePassword(alice, here.token(), "motdepasse-alice", "motdepasse-alice"));
        assertEquals(AccountService.PasswordChange.CHANGED,
                service.changePassword(alice, here.token(), "motdepasse-alice", "nouveau-secret-1"));

        assertTrue(service.session(here.token()).isPresent(), "la session qui a changé le mot de passe reste ouverte");
        assertTrue(service.session(elsewhere.token()).isEmpty(), "les autres sessions sont révoquées");
        assertTrue(service.login("alice", "motdepasse-alice", "ip3").isEmpty());
        assertTrue(service.login("alice", "nouveau-secret-1", "ip3").isPresent());
    }

    @Test
    void wrongCurrentPasswordsAreThrottledPerAccount() throws Exception {
        String alice = registerAndLogin("alice");
        AccountService.NewSession session = service.login("alice", "motdepasse-alice", "ip").orElseThrow();
        for (int i = 0; i < 10; i++) {
            service.changePassword(alice, session.token(), "faux-" + i, "nouveau-secret-1");
        }
        assertEquals(AccountService.PasswordChange.TOO_MANY,
                service.changePassword(alice, session.token(), "motdepasse-alice", "nouveau-secret-1"));
        assertFalse(service.deleteAccount(alice, "motdepasse-alice"), "même limite pour la suppression");
        clock.advance(Duration.ofMinutes(16));
        assertEquals(AccountService.PasswordChange.CHANGED,
                service.changePassword(alice, session.token(), "motdepasse-alice", "nouveau-secret-1"));
    }

    @Test
    void exportContainsEverythingKeptButNoSecret() throws Exception {
        String alice = registerAndLogin("alice");
        AccountService.NewSession session = service.login("alice", "motdepasse-alice", "ip").orElseThrow();
        assertEquals(LinkOutcome.LINKED, service.confirmLink(newCode(alice), ALICE_UUID, "AliceMC"));

        Map<String, Object> export = service.export(alice);
        String json = com.lodygames.rpgquest.webapi.json.Json.write(export);
        assertEquals("lodyquests-portal-account-export", export.get("format"));
        assertTrue(json.contains("\"login\":\"alice\""));
        assertTrue(json.contains(ALICE_UUID.toString()));
        assertTrue(json.contains("\"action\":\"LINKED\""));
        assertTrue(json.contains("\"openSessions\":[{"));
        assertTrue(json.contains("\"progress\":null"), "aucune progression inventée");
        String hash = store.findAccount(alice).orElseThrow().passwordHash();
        assertFalse(json.contains(hash), "jamais l'empreinte du mot de passe");
        assertFalse(json.contains(session.token()) || json.contains(SecureTokens.sha256(session.token())));
        assertFalse(json.contains(session.csrfToken()));
    }

    @Test
    void deletionErasesAccountLinkHistoryAndSessionsAndFreesTheUuid() throws Exception {
        String alice = registerAndLogin("alice");
        AccountService.NewSession session = service.login("alice", "motdepasse-alice", "ip").orElseThrow();
        assertEquals(LinkOutcome.LINKED, service.confirmLink(newCode(alice), ALICE_UUID, "AliceMC"));

        assertFalse(service.deleteAccount(alice, "faux"));
        assertTrue(store.findAccount(alice).isPresent());
        assertTrue(service.deleteAccount(alice, "motdepasse-alice"));

        assertTrue(store.findAccount(alice).isEmpty());
        assertTrue(store.findLink(alice).isEmpty());
        assertTrue(store.auditOf(alice, 100).isEmpty(), "historique effacé");
        assertTrue(service.session(session.token()).isEmpty(), "sessions révoquées");
        assertFalse(service.isLinked(ALICE_UUID));
        assertTrue(service.login("alice", "motdepasse-alice", "ip").isEmpty());

        String bob = registerAndLogin("bob");
        assertEquals(LinkOutcome.LINKED, service.confirmLink(newCode(bob), ALICE_UUID, "AliceMC"),
                "l'UUID redevient libre");
    }
}
