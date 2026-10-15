package com.lodygames.rpgquest.webapi.news;

/** Fichier d'actualité refusé au chargement ; le message dit quoi corriger, sans recopier le contenu. */
public final class NewsFormatException extends Exception {

    public NewsFormatException(String message) {
        super(message);
    }
}
