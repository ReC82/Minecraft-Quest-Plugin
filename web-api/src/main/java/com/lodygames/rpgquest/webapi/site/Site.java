package com.lodygames.rpgquest.webapi.site;

/**
 * Identité du portail public, partagée par les pages (#56).
 *
 * @param title   nom affiché ({@code site-title})
 * @param baseUrl adresse publique sans « / » final ({@code public-base-url}), pour les liens canoniques et le sitemap
 */
public record Site(String title, String baseUrl) {

    public Site {
        baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }
}
