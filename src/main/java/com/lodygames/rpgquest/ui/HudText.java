package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.economy.CurrencyName;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Textes du HUD pièces / niveau RPG (issue #184), sans dépendance au serveur pour être testés.
 * Le solde vient toujours du portefeuille réel ; {@code null} = pas encore lu (affiché « … »,
 * jamais un faux 0).
 */
final class HudText {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private HudText() {
    }

    static Component title() {
        return MM.deserialize("<gold><bold>LodyQuests</bold></gold>");
    }

    static Component coinsLine(Long balance) {
        return MM.deserialize("<gray>Bourse :</gray> <gold><amount></gold> <yellow><currency></yellow>",
                Placeholder.unparsed("amount", balance == null ? "…" : String.valueOf(balance)),
                Placeholder.unparsed("currency", CurrencyName.get()));
    }

    static Component levelLine(int level) {
        return MM.deserialize("<gray>Niveau RPG :</gray> <aqua><level></aqua>",
                Placeholder.unparsed("level", String.valueOf(level)));
    }

    /** Retour temporaire d'un mouvement ; {@code delta == 0} = solde fixé par un administrateur. */
    static Component walletChange(long delta, long balance) {
        String template = delta > 0
                ? "<green>+<amount> <currency></green> <gray>· solde <balance></gray>"
                : delta < 0
                        ? "<red>-<amount> <currency></red> <gray>· solde <balance></gray>"
                        : "<yellow>Solde ajusté :</yellow> <gold><balance> <currency></gold>";
        return MM.deserialize(template,
                Placeholder.unparsed("amount", String.valueOf(Math.abs(delta))),
                Placeholder.unparsed("currency", CurrencyName.get()),
                Placeholder.unparsed("balance", String.valueOf(balance)));
    }
}
