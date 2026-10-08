package com.lodygames.rpgquest.panel.publish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * L'état du contenu sur DEV, tel que le panel le lit (issue #47).
 *
 * <p>Construit depuis le dernier relevé {@code content.dev.state} <strong>réussi</strong>. Sans
 * relevé, l'index est {@link #available() indisponible} et toutes les ressources tombent en
 * {@link PublishState#UNKNOWN} : afficher « Source uniquement » sans avoir interrogé le serveur
 * serait une affirmation gratuite, et c'est précisément ce que #47 reproche à l'état antérieur.</p>
 *
 * <p>Deux informations distinctes y cohabitent, et elles ne disent pas la même chose : le
 * <strong>fichier</strong> présent sur le disque du serveur, et l'<strong>identifiant</strong>
 * réellement chargé par le moteur. Un fichier peut exister sans être chargé ; c'est l'état
 * {@code NOT_LOADED}, et c'est un vrai problème que l'ancien modèle ne savait pas nommer.</p>
 */
public record DevContentIndex(Map<String, String> fileShaByKey,
                              Map<String, List<String>> runtimeIdsByKind,
                              String runtimeHash,
                              boolean available) {

    public DevContentIndex {
        fileShaByKey = Map.copyOf(fileShaByKey == null ? Map.of() : fileShaByKey);
        runtimeIdsByKind = Map.copyOf(runtimeIdsByKind == null ? Map.of() : runtimeIdsByKind);
        runtimeHash = runtimeHash == null ? "" : runtimeHash;
    }

    public static DevContentIndex unavailable() {
        return new DevContentIndex(Map.of(), Map.of(), "", false);
    }

    /** Projette les détails d'un {@code content.dev.state}. {@code null} ⇒ indisponible. */
    public static DevContentIndex from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (Object raw : asList(details.get("files"))) {
            Map<String, Object> row = asMap(raw);
            String kind = str(row.get("kind"));
            String slug = str(row.get("slug"));
            if (!kind.isEmpty() && !slug.isEmpty()) {
                files.put(key(kind, slug), str(row.get("sha256")));
            }
        }
        Map<String, List<String>> runtime = new LinkedHashMap<>();
        Object rawRuntime = details.get("runtimeIds");
        if (rawRuntime instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                List<String> ids = asList(entry.getValue()).stream()
                        .map(DevContentIndex::str)
                        .filter(value -> !value.isEmpty())
                        .toList();
                runtime.put(str(entry.getKey()).toLowerCase(Locale.ROOT), ids);
            }
        }
        return new DevContentIndex(files, runtime, str(details.get("runtimeHash")), true);
    }

    /** L'empreinte du fichier DEV, ou {@code ""} s'il est absent (ou l'index indisponible). */
    public String devSha(String kind, String slug) {
        return fileShaByKey.getOrDefault(key(kind, slug), "");
    }

    public boolean present(String kind, String slug) {
        return !devSha(kind, slug).isEmpty();
    }

    /**
     * Le moteur porte-t-il cet identifiant ?
     *
     * <p>Comparaison sur la forme « nue » : le panel raisonne en slugs, le moteur en
     * {@code rpgquest:<id>}. Réconcilier ici une fois évite de le refaire — donc de le refaire
     * différemment — à chaque page.</p>
     */
    public boolean runtimeLoaded(String kind, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String wanted = plain(id);
        for (String loaded : runtimeIdsByKind.getOrDefault(
                kind.toLowerCase(Locale.ROOT), List.of())) {
            if (plain(loaded).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Les identifiants chargés pour une famille, tels que le moteur les porte. */
    public List<String> runtimeIds(String kind) {
        return runtimeIdsByKind.getOrDefault(kind.toLowerCase(Locale.ROOT), List.of());
    }

    /** Les identifiants présents sur DEV pour une famille, d'après les fichiers. */
    public List<String> devSlugs(String kind) {
        String prefix = kind.toLowerCase(Locale.ROOT) + "/";
        List<String> out = new ArrayList<>();
        for (String k : fileShaByKey.keySet()) {
            if (k.startsWith(prefix)) {
                out.add(k.substring(prefix.length()));
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** L'état d'une ressource, en combinant source et DEV. */
    public PublishState stateOf(String kind, String slug, String expectedId,
                                String sourceSha, boolean sourceKnown, String lastPublished) {
        return PublishState.of(available, sourceKnown, sourceSha, devSha(kind, slug),
                runtimeLoaded(kind, expectedId == null || expectedId.isBlank() ? slug : expectedId),
                lastPublished);
    }

    private static String key(String kind, String slug) {
        return kind.toLowerCase(Locale.ROOT) + "/" + slug.toLowerCase(Locale.ROOT);
    }

    private static String plain(String id) {
        String clean = id.trim().toLowerCase(Locale.ROOT);
        int colon = clean.indexOf(':');
        return colon < 0 ? clean : clean.substring(colon + 1);
    }

    private static List<Object> asList(Object raw) {
        return raw instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
