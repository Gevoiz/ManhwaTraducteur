# Manhwa Traducteur

Une appli Android qui traduit les bulles de manhwa/webtoon/manga **en superposition**, par-dessus n'importe quelle appli de lecture (navigateur, Webtoon, Tachiyomi/Mihon…).

## Télécharger

👉 [Dernière version (APK)](https://github.com/Gevoiz/ManhwaTraducteur/releases/latest) : Android 8.0+, téléphones ARM.

Copie l'APK sur ton téléphone, ouvre-le et autorise « installer des applis inconnues ».

## Comment ça marche

1. Une bulle flottante rouge reste au-dessus de tes applis.
2. Quand tu la touches, l'appli fait une capture d'écran (MediaProjection).
3. **ML Kit Text Recognition** repère les blocs de texte et leur position.
4. **ML Kit Translation** traduit chaque bloc **sur le téléphone** (hors-ligne une fois le modèle téléchargé, ~30 Mo par langue).
5. Un cartouche blanc avec la traduction est dessiné pile sur chaque bulle d'origine.

| Geste sur la bulle | Effet |
|---|---|
| Toucher | Traduire l'écran |
| Appui long | Effacer la traduction |
| Glisser | Déplacer la bulle |

Options : langue source (anglais, coréen, japonais, chinois), langue cible (français par défaut), effacement automatique quand tu fais défiler, et un **mode auto** qui retraduit tout seul dès que l'écran s'arrête de bouger.

## Compiler et installer

1. Installer **Android Studio** (gratuit).
2. *File → Open* → choisir le dossier `ManhwaTraducteur`. Android Studio télécharge Gradle et le SDK tout seul.
3. Brancher le téléphone (débogage USB activé) → bouton ▶ **Run**.
   Ou *Build → Build APK(s)* → l'APK est dans `app/build/outputs/apk/debug/`.

Au premier lancement : autoriser « l'affichage par-dessus les autres applis », puis **Démarrer** et accepter la capture d'écran (choisir **écran entier**).

## Limites connues

- La traduction ML Kit est correcte mais « mot à mot » : moins naturelle qu'un vrai traducteur ou qu'un LLM.
- L'OCR peut rater du texte très stylisé (onomatopées, polices manuscrites, texte en biais).
- Android 12+ impose qu'une superposition qui laisse passer les touchers soit opaque à 80 % max : le texte d'origine se devine légèrement derrière le cartouche.
- Les jeux ou applis protégées (DRM, `FLAG_SECURE`) donnent une capture noire.
