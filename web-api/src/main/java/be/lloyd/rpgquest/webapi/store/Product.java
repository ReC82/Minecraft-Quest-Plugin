package be.lloyd.rpgquest.webapi.store;

/**
 * Un produit vendable, uniquement des données commerciales (nom, prix) —
 * jamais ce qu'il accorde en jeu (mission étape 22, point 1 : "catalogue de
 * produits séparé des avantages techniques"). Le mapping produit → avantage
 * (taille de backpack, avantage générique...) n'existe que côté plugin
 * (`store.StoreProductRegistry`), jamais ici : web-api ne sait littéralement
 * pas ce qu'un identifiant de produit "fait" en jeu.
 */
public record Product(String id, String name, long priceCents, String currency) {
}
