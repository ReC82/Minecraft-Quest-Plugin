package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Administration de la monnaie depuis le panel (issue #140, premier lot économie).
 *
 * <p>Deux propriétés sont figées ici, et ce sont celles qui protègent l'économie :
 * <strong>lire un solde et en créer ne sont pas le même geste</strong> (deux permissions), et
 * <strong>aucune opération n'est possible sans raison</strong>, parce que la raison part dans le
 * journal des transactions — sans elle, une création administrative serait indiscernable d'un gain
 * de jeu quelques mois plus tard.</p>
 */
class EconomyAdminTest {

    // ---- Permissions -------------------------------------------------------------------------

    @Test
    void readingABalanceAndCreatingMoneyAreTwoDifferentPermissions() {
        assertEquals(Permission.ECONOMY_READ,
                AgentActionCatalog.spec("economy.balance").orElseThrow().permission());
        assertEquals(Permission.ECONOMY_WRITE,
                AgentActionCatalog.spec("economy.credit").orElseThrow().permission());
        assertEquals(Permission.ECONOMY_WRITE,
                AgentActionCatalog.spec("economy.debit").orElseThrow().permission());
    }

    @Test
    void aTesterMaySeeABalanceButNeverCreateMoney() {
        // Diagnostiquer un comportement en jeu demande de voir le solde ; en créer, non.
        assertTrue(Role.TESTER.has(Permission.ECONOMY_READ));
        assertFalse(Role.TESTER.has(Permission.ECONOMY_WRITE));
        assertTrue(Role.READ_ONLY.has(Permission.ECONOMY_READ));
        assertFalse(Role.READ_ONLY.has(Permission.ECONOMY_WRITE));
    }

    @Test
    void anAdminAdministersTheCurrencyAndAnOwnerKeepsEverything() {
        assertTrue(Role.ADMIN.has(Permission.ECONOMY_READ));
        assertTrue(Role.ADMIN.has(Permission.ECONOMY_WRITE));
        assertTrue(Role.OWNER.has(Permission.ECONOMY_WRITE));
    }

    @Test
    void contentRolesHaveNoEconomyAccessAtAll() {
        for (Role role : new Role[] {Role.BUILDER, Role.CONTENT_EDITOR}) {
            assertFalse(role.has(Permission.ECONOMY_READ), role + " n'a rien à voir avec la monnaie");
            assertFalse(role.has(Permission.ECONOMY_WRITE), role.toString());
        }
    }

    @Test
    void readingIsALookupWhileCreditAndDebitAreSensitiveMutations() {
        var read = AgentActionCatalog.spec("economy.balance").orElseThrow();
        assertFalse(read.mutation(), "lire un solde ne change rien");
        assertTrue(read.needsPlayer());

        for (String type : new String[] {"economy.credit", "economy.debit"}) {
            var spec = AgentActionCatalog.spec(type).orElseThrow();
            assertTrue(spec.mutation(), type);
            assertTrue(spec.sensitive(), type + " doit exiger une confirmation : un crédit crée de la monnaie");
            assertTrue(spec.needsPlayer(), type);
            assertTrue(spec.refreshTypes().contains("player.catalog"),
                    type + " doit rafraîchir l'annuaire pour que le solde affiché soit relu");
        }
    }

    // ---- Validation --------------------------------------------------------------------------

    @Test
    void aReasonIsMandatoryBecauseItLandsInTheLedger() {
        AgentActionCatalog.Validation missing = AgentActionCatalog.validate("economy.credit",
                Map.of("player", "Steve", "amount", "100", "confirm", "true"));

        assertFalse(missing.valid());
        assertTrue(missing.error().contains("journal"), missing.error());
    }

    @Test
    void anAmountMustBeAStrictlyPositiveInteger() {
        for (String amount : new String[] {"0", "-1", "abc", "", "1.5"}) {
            AgentActionCatalog.Validation invalid = AgentActionCatalog.validate("economy.credit",
                    Map.of("player", "Steve", "amount", amount, "reason", "test", "confirm", "true"));
            assertFalse(invalid.valid(), "montant « " + amount + " » doit être refusé");
        }
    }

    @Test
    void aTypoWithSixZeroesCannotCreateAFortuneInOneClick() {
        AgentActionCatalog.Validation tooBig = AgentActionCatalog.validate("economy.credit",
                Map.of("player", "Steve", "amount",
                        Long.toString(AgentActionCatalog.MAX_ECONOMY_AMOUNT + 1),
                        "reason", "faute de frappe", "confirm", "true"));

        // Garde-fou de SAISIE, pas une règle d'équilibrage — et c'est dit dans le message.
        assertFalse(tooBig.valid());
        assertTrue(tooBig.error().contains("trop élevé"), tooBig.error());

        AgentActionCatalog.Validation atLimit = AgentActionCatalog.validate("economy.credit",
                Map.of("player", "Steve", "amount",
                        Long.toString(AgentActionCatalog.MAX_ECONOMY_AMOUNT),
                        "reason", "limite exacte", "confirm", "true"));
        assertTrue(atLimit.valid(), atLimit.error());
    }

    @Test
    void aValidCreditCarriesItsAmountAndReason() {
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("economy.credit",
                Map.of("player", "Steve", "amount", "250", "reason", "compensation bug #42",
                        "confirm", "true"));

        assertTrue(ok.valid(), ok.error());
        assertEquals("250", ok.params().get("amount"));
        assertEquals("compensation bug #42", ok.params().get("reason"));
        assertEquals("Steve", ok.params().get("player"));
    }

    @Test
    void creditAndDebitBothRequireTheExplicitConfirmation() {
        for (String type : new String[] {"economy.credit", "economy.debit"}) {
            AgentActionCatalog.Validation missing = AgentActionCatalog.validate(type,
                    Map.of("player", "Steve", "amount", "10", "reason", "test"));
            assertFalse(missing.valid(), type);
            assertTrue(missing.error().contains("Confirmation"), missing.error());
        }
    }

    @Test
    void aMultilineOrOversizedReasonIsRejected() {
        assertFalse(AgentActionCatalog.validate("economy.debit",
                Map.of("player", "Steve", "amount", "10", "reason", "a\nb", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("economy.debit",
                Map.of("player", "Steve", "amount", "10", "reason", "x".repeat(201),
                        "confirm", "true")).valid());
    }

    @Test
    void theLedgerLengthIsOptionalAndBounded() {
        assertTrue(AgentActionCatalog.validate("economy.balance", Map.of("player", "Steve")).valid());
        assertTrue(AgentActionCatalog.validate("economy.balance",
                Map.of("player", "Steve", "history", "50")).valid());
        assertFalse(AgentActionCatalog.validate("economy.balance",
                Map.of("player", "Steve", "history", "0")).valid());
        assertFalse(AgentActionCatalog.validate("economy.balance",
                Map.of("player", "Steve", "history", "101")).valid());
        assertFalse(AgentActionCatalog.validate("economy.balance",
                Map.of("player", "Steve", "history", "beaucoup")).valid());
    }

    @Test
    void aPlayerIsAlwaysRequired() {
        for (String type : new String[] {"economy.balance", "economy.credit", "economy.debit"}) {
            assertFalse(AgentActionCatalog.validate(type,
                    Map.of("amount", "10", "reason", "test", "confirm", "true")).valid(), type);
        }
    }

    // ---- Récompenses monétaires restées dues (issue #16, second lot) ---------------------------

    @Test
    void readingPendingRewardsAndSettlingThemAreTwoDifferentPermissions() {
        assertEquals(Permission.ECONOMY_READ,
                AgentActionCatalog.spec("economy.debts").orElseThrow().permission());
        assertEquals(Permission.ECONOMY_WRITE,
                AgentActionCatalog.spec("economy.debt.retry").orElseThrow().permission());
        assertEquals(Permission.ECONOMY_WRITE,
                AgentActionCatalog.spec("economy.debt.settle").orElseThrow().permission());
    }

    @Test
    void aTesterSeesWhatIsOwedButCannotSettleIt() {
        // Diagnostiquer « je n'ai pas été payé » demande de voir la dette ; la régler, non.
        assertTrue(Role.TESTER.has(Permission.ECONOMY_READ));
        assertFalse(Role.TESTER.has(Permission.ECONOMY_WRITE));
        assertTrue(Role.READ_ONLY.has(Permission.ECONOMY_READ));
        assertFalse(Role.READ_ONLY.has(Permission.ECONOMY_WRITE));
    }

    @Test
    void retryingAndSettlingAreSensitiveMutationsThatRefreshTheDirectory() {
        assertFalse(AgentActionCatalog.spec("economy.debts").orElseThrow().mutation(),
                "lire ce qui est dû ne change rien");

        for (String type : new String[] {"economy.debt.retry", "economy.debt.settle"}) {
            var spec = AgentActionCatalog.spec(type).orElseThrow();
            assertTrue(spec.mutation(), type);
            assertTrue(spec.sensitive(), type + " touche à l'argent : confirmation exigée");
            assertTrue(spec.needsPlayer(), type);
            assertTrue(spec.refreshTypes().contains("player.catalog"), type);
        }
    }

    @Test
    void aRetryTakesNoAmountAtAllSoNoneCanBeInvented() {
        // Le point de sécurité : une reprise paie ce qui a été enregistré à la complétion. Si un
        // montant était accepté ici, un administrateur pourrait en inventer un.
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("economy.debt.retry",
                Map.of("player", "Steve", "grant", "token-a#0", "amount", "999999", "confirm", "true"));

        assertTrue(ok.valid(), ok.error());
        assertEquals("token-a#0", ok.params().get("grant"));
        assertFalse(ok.params().containsKey("amount"),
                "aucun montant ne doit être transmis : la dette porte le sien");
    }

    @Test
    void anIdentityIsMandatoryForBothOperations() {
        for (String type : new String[] {"economy.debt.retry", "economy.debt.settle"}) {
            AgentActionCatalog.Validation missing = AgentActionCatalog.validate(type,
                    Map.of("player", "Steve", "reason", "test", "confirm", "true"));
            assertFalse(missing.valid(), type);
            assertTrue(missing.error().contains("Identifiant"), missing.error());
        }
    }

    @Test
    void settlingManuallyDemandsAReasonBecauseItIsTheOnlyTraceOfTheCompensation() {
        AgentActionCatalog.Validation missing = AgentActionCatalog.validate("economy.debt.settle",
                Map.of("player", "Steve", "grant", "token-a#0", "confirm", "true"));

        assertFalse(missing.valid());
        assertTrue(missing.error().contains("raison"), missing.error());

        assertFalse(AgentActionCatalog.validate("economy.debt.settle",
                Map.of("player", "Steve", "grant", "token-a#0", "reason", "a\nb", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("economy.debt.settle",
                Map.of("player", "Steve", "grant", "token-a#0", "reason", "x".repeat(201),
                        "confirm", "true")).valid());

        assertTrue(AgentActionCatalog.validate("economy.debt.settle",
                Map.of("player", "Steve", "grant", "token-a#0", "reason", "compensé à la main",
                        "confirm", "true")).valid());
    }

    @Test
    void bothOperationsRequireTheExplicitConfirmation() {
        for (String type : new String[] {"economy.debt.retry", "economy.debt.settle"}) {
            AgentActionCatalog.Validation missing = AgentActionCatalog.validate(type,
                    Map.of("player", "Steve", "grant", "token-a#0", "reason", "test"));
            assertFalse(missing.valid(), type);
            assertTrue(missing.error().contains("Confirmation"), missing.error());
        }
    }

    @Test
    void theSurveyLengthIsOptionalAndBounded() {
        assertTrue(AgentActionCatalog.validate("economy.debts", Map.of("player", "Steve")).valid());
        assertTrue(AgentActionCatalog.validate("economy.debts",
                Map.of("player", "Steve", "limit", "50")).valid());
        assertFalse(AgentActionCatalog.validate("economy.debts",
                Map.of("player", "Steve", "limit", "0")).valid());
        assertFalse(AgentActionCatalog.validate("economy.debts",
                Map.of("player", "Steve", "limit", "101")).valid());
    }

    @Test
    void anOverlongIdentityIsRefused() {
        assertFalse(AgentActionCatalog.validate("economy.debt.retry",
                Map.of("player", "Steve", "grant", "x".repeat(129), "confirm", "true")).valid());
    }
}
