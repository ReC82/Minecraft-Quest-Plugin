package com.lodygames.rpgquest.panel.docs;

import java.util.List;

/**
 * Une fiche de documentation chargée en mémoire (issue #49). Git/Markdown reste la source de
 * vérité : cet objet n'est qu'un index de lecture, jamais réécrit, jamais persisté en base.
 *
 * @param slug        identifiant interne stable ({@code [a-z0-9-]}), résolu côté serveur — jamais
 *                    un chemin fourni par le navigateur
 * @param title       titre humain (front matter {@code title}, sinon 1er {@code # …}, sinon slug)
 * @param category    catégorie humaine (front matter {@code category}, sinon « Divers »)
 * @param tags        étiquettes normalisées minuscules
 * @param order       ordre d'affichage dans sa catégorie (croissant)
 * @param markdown    corps Markdown brut (sans front matter)
 * @param sourcePath  chemin de la ressource d'origine, pour affichage (« Source : … »)
 * @param commands    lignes de commande repérées dans la fiche (blocs de code + code inline
 *                    ressemblant à une commande) — utilisées par la recherche
 */
public record DocPage(String slug, String title, String category, List<String> tags, int order,
                      String markdown, String sourcePath, List<String> commands) {

    public DocPage {
        tags = tags == null ? List.of() : List.copyOf(tags);
        commands = commands == null ? List.of() : List.copyOf(commands);
    }
}
