# Assets tiers embarqués dans le Control Panel (servis localement, aucun CDN au runtime)

| Dossier | Composant | Version | Licence | Source |
|---|---|---|---|---|
| `bootstrap/` | Bootstrap (CSS + bundle JS avec Popper) | 5.3.8 | MIT | https://getbootstrap.com — `npm:bootstrap@5.3.8/dist/{css/bootstrap.min.css,js/bootstrap.bundle.min.js}` |
| `bootstrap-icons/` | Bootstrap Icons (police + CSS) | 1.13.1 | MIT | https://icons.getbootstrap.com — `npm:bootstrap-icons@1.13.1/font/{bootstrap-icons.min.css,fonts/bootstrap-icons.woff2,fonts/bootstrap-icons.woff}` |

Fichiers vendored **tels quels**, non modifiés. Mise à jour : re-télécharger la même arborescence
depuis jsdelivr/npm et remplacer les fichiers (les chemins de police restent relatifs
`fonts/bootstrap-icons.woff2`).

`plugadmin.css` (dans ce dossier) est la couche d'identité PlugAdmin **par-dessus** Bootstrap
(tokens #92, pont `--bs-*`, tuiles de la Home). `panel.js` est le script progressif maison.

Tout est servi par `PanelApp#handleAsset` sous `/assets/…` (même origine), conforme à la CSP
`default-src 'self'`.
