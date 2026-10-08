package com.lodygames.rpgquest.building.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Un plan de bâtiment complet, en coordonnées locales (issue #213, lot « placement »).
 *
 * <h2>Le plan est la source de vérité, pas le fichier</h2>
 *
 * <p>Le {@code .schem} est un <strong>artefact dérivé</strong> : un blob gzip de quelques kilo-octets
 * que personne ne peut relire en revue. Le plan, lui, est du code — on peut le lire, le tester, et
 * constater que la porte est bien au centre de la façade. C'est pour cette raison que la hutte de
 * test est <em>générée</em> plutôt que fournie : un fichier binaire déposé dans le dépôt ne se
 * vérifie pas, et aucune relecture ne dirait qu'il mesure 7 × 5 × 6.</p>
 *
 * <p>Aucun type WorldEdit ici non plus. Le plan décrit des blocs ; l'écriture du fichier est le
 * travail de l'adaptateur.</p>
 *
 * <p>Les cellules non listées sont de l'<strong>air</strong> : un plan ne décrit que la matière.
 * C'est ce qui rend l'intérieur vide gratuit, et le plan lisible.</p>
 */
public record Blueprint(int sizeX, int sizeY, int sizeZ, List<BlueprintBlock> blocks) {

    public Blueprint {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException(
                    "dimensions invalides : " + sizeX + " / " + sizeY + " / " + sizeZ);
        }
        blocks = List.copyOf(blocks == null ? List.of() : blocks);
    }

    /**
     * Vérifie que le plan est cohérent avec lui-même.
     *
     * <p>Deux fautes sont possibles et silencieuses : un bloc hors des dimensions annoncées (le
     * fichier serait alors plus grand que la définition, et l'emprise annoncée fausse), et deux
     * blocs à la même position (le résultat dépendrait de l'ordre d'écriture, donc le fichier ne
     * serait plus déterministe). Les deux sont refusées ici.</p>
     *
     * @return le motif du refus, ou {@link Optional#empty()} si le plan est exploitable
     */
    public Optional<String> validate() {
        Set<Long> seen = new LinkedHashSet<>();
        for (BlueprintBlock block : blocks) {
            if (block.x() >= sizeX || block.y() >= sizeY || block.z() >= sizeZ) {
                return Optional.of("Bloc hors des dimensions du plan : " + block.x() + " / "
                        + block.y() + " / " + block.z() + " dans " + sizeX + " × " + sizeZ
                        + " × " + sizeY + ".");
            }
            long key = ((long) block.x() << 40) | ((long) block.y() << 20) | block.z();
            if (!seen.add(key)) {
                return Optional.of("Deux blocs à la même position : " + block.x() + " / "
                        + block.y() + " / " + block.z() + " — le fichier ne serait pas "
                        + "déterministe.");
            }
        }
        return Optional.empty();
    }

    public boolean valid() {
        return validate().isEmpty();
    }

    public Optional<BlueprintBlock> at(int x, int y, int z) {
        return blocks.stream()
                .filter(b -> b.x() == x && b.y() == y && b.z() == z)
                .findFirst();
    }

    /** Les types de blocs utilisés, dans l'ordre de première apparition. */
    public List<String> materials() {
        Set<String> types = new LinkedHashSet<>();
        for (BlueprintBlock block : blocks) {
            types.add(block.material());
        }
        return new ArrayList<>(types);
    }

    /** Le nombre de blocs de matière — l'air n'étant pas listé, c'est aussi le volume plein. */
    public int solidCount() {
        return blocks.size();
    }
}
