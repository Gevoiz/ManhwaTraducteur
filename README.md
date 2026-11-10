# Manhwa Traducteur

Une appli Android qui traduit les bulles de manhwa/webtoon/manga **en superposition**, par-dessus n'importe quelle appli de lecture (navigateur, Webtoon, Tachiyomi/Mihon…).

## Télécharger

👉 [Dernière version (APK)](https://github.com/Gevoiz/ManhwaTraducteur/releases/latest) : Android 8.0+, téléphones ARM.

Copie l'APK sur ton téléphone, ouvre-le et autorise « installer des applis inconnues ».

## Comment ça marche

1. Une bulle flottante rouge reste au-dessus de tes applis.
2. Quand tu la touches, l'appli fait une capture d'écran (MediaProjection).
3. **ML Kit Text Recognition** repère les blocs de texte et leur position.
4. Traduction, au choix :
   - **Hors-ligne (ML Kit)** : sur le téléphone, sans compte (~30 Mo de modèle par langue, téléchargé une fois). Rapide mais littéral.
   - **IA Gemini** (en ligne) : la capture et les textes partent chez Google, qui corrige les erreurs d'OCR grâce à l'image et traduit toute la page d'un coup, en gardant les noms cohérents d'une page à l'autre. Clé gratuite sur [Google AI Studio](https://aistudio.google.com/apikey). En cas d'erreur ou de quota atteint, l'appli repasse toute seule en hors-ligne.
5. Un cartouche blanc avec la traduction est dessiné pile sur chaque bulle d'origine.
6. Quand tu fais défiler, l'appli mesure le décalage de la page et **les traductions suivent**. Si la page change complètement (nouveau chapitre, zoom), elles s'effacent.

| Geste sur la bulle | Effet |
|---|---|
| Toucher | Traduire l'écran |
| Appui long | Effacer la traduction |
| Glisser | Déplacer la bulle |

Options : langue source (anglais, coréen, japonais, chinois), langue cible (français par défaut), moteur de traduction, suivi du défilement, et un **mode auto** qui retraduit tout seul quand l'écran s'arrête après un bon défilement.

## Compiler et installer

1. Installer **Android Studio** (gratuit).
2. *File → Open* → choisir le dossier `ManhwaTraducteur`. Android Studio télécharge Gradle et le SDK tout seul.
3. Brancher le téléphone (débogage USB activé) → bouton ▶ **Run**.
   Ou *Build → Build APK(s)* → l'APK est dans `app/build/outputs/apk/debug/`.

Au premier lancement : autoriser « l'affichage par-dessus les autres applis », puis **Démarrer** et accepter la capture d'écran (choisir **écran entier**).

## Limites connues

- La traduction ML Kit est correcte mais « mot à mot » : passe sur Gemini pour un rendu naturel.
- Avec la clé Gemini gratuite, Google peut utiliser les captures envoyées pour améliorer ses modèles, et le nombre de requêtes par minute est limité.
- Le suivi du défilement ne gère que le défilement vertical (pas le zoom ni le glissement horizontal).
- L'OCR peut rater du texte très stylisé (onomatopées, polices manuscrites, texte en biais).
- Android 12+ impose qu'une superposition qui laisse passer les touchers soit opaque à 80 % max : le texte d'origine se devine légèrement derrière le cartouche.
- Les jeux ou applis protégées (DRM, `FLAG_SECURE`) donnent une capture noire.

## Licence

[MIT](LICENSE)
