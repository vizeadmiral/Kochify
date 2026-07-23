# Kochify

> Mein Larp

Android-Musikplayer im dunklen Streaming-Stil mit:

- lokaler MP3-Bibliothek und Offline-Wiedergabe
- MP3-Import über den Android-Dateidialog
- Suche, Favoriten und eigene Playlists
- bearbeitbaren Titeln, Interpreten und Covern
- Download einzelner eigener/freigegebener Videos oder kompletter Playlists als MP3
- sichtbarer Rechtebestätigung vor jedem Link-Download

## APK in Android Studio erzeugen

1. Den Ordner `Kochify` in Android Studio öffnen.
2. Die automatische Gradle-Synchronisierung abwarten.
3. Ein verbundenes Android-Gerät oder einen Emulator auswählen und die App testen.
4. `Build` → `Generate App Bundles or APKs` → `Generate APKs` wählen.
5. Die Debug-APK liegt anschließend unter `app/build/outputs/apk/debug/app-debug.apk`
   und kann in `Kochify.apk` umbenannt werden.

Für eine veröffentlichbare Version unter `Build` → `Generate Signed App Bundle or APK`
einen eigenen Signaturschlüssel anlegen.

Alternativ enthält das Projekt unter `.github/workflows/build-apk.yml` einen
GitHub-Actions-Build. Nach dem Hochladen in ein Repository lässt sich der
Workflow „Android APK“ manuell starten; die fertige Debug-APK erscheint dort als
Artefakt `Kochify-APK`.

## Rechtlicher Hinweis

Die Link-Funktion ist ausschließlich für eigene Inhalte sowie Medien vorgesehen,
für die eine ausdrückliche Download- und Umwandlungserlaubnis vorliegt. Vor jedem
Download muss dies in der App bestätigt werden. Die Verantwortung für die
verwendeten Links liegt beim Nutzer.

## Technische Hinweise

- Mindestversion: Android 8.0 (API 26)
- Zielversion: Android 15 (API 35)
- Link-Verarbeitung: `youtubedl-android` 0.18.1 mit FFmpeg
- Die Bibliothek `youtubedl-android` steht unter GPL-3.0. Eine veröffentlichte,
  abgeleitete App muss die einschlägigen Lizenzpflichten erfüllen.
