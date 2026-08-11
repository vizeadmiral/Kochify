# Kochify

> Mein Larp

Android-Musikplayer im dunklen Streaming-Stil mit:

- lokaler MP3-Bibliothek und Offline-Wiedergabe
- MP3-Import über den Android-Dateidialog
- Suche, Favoriten und eigene Playlists
- Shuffle, automatische Weiterschaltung und Endloswiederholung einzelner Titel
- bearbeitbaren Titeln, Interpreten und Covern
- automatischen YouTube-Thumbnails, eigenen Download-Covern und Cover-Mehrfachauswahl
- vollständiger Sicherung und Wiederherstellung von Song- und Playlistbildern
- Playlist-Editor mit Ziehgriffen für eine einfache Reihenfolge per Drag & Drop
- Mehrfachimport lokaler MP3-Dateien und Mehrfachzuordnung zu mehreren Playlists
- nativer Auswahlmodus durch langes Drücken direkt in jeder Songliste
- Shuffle-Zyklen ohne Wiederholung, bis jeder Song der Wiedergabeliste gespielt wurde
- Cyberpunk- und Deutschland-Design sowie ein stärker animierter RGB-Modus
- Übernahme eigener und gemeinsam bearbeiteter Spotify-Playlists
- Download einzelner eigener/freigegebener Videos oder kompletter Playlists als MP3
- automatische Kochify-Playlist, Gesamtfortschritt und Weiterlaufen bei einzelnen Fehlern
- sichtbarer Rechtebestätigung vor jedem Link-Download

## Spotify-Playlists importieren

Kochify nutzt die offizielle Spotify-Anmeldung mit Authorization Code und PKCE.
Spotify-Audiodateien werden nicht kopiert. Playlistnamen, Titel und Interpreten
werden übernommen und mit vorhandenen MP3s abgeglichen. Fehlende Titel bleiben
vorgemerkt und werden beim späteren MP3-Import automatisch einsortiert.

Einmalige Einrichtung:

1. Im [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
   eine App für Kochify erstellen.
2. In den App-Einstellungen als Redirect-URI
   `kochify://spotify-callback` eintragen.
3. Die dort angezeigte Client-ID kopieren.
4. In Kochify auf das grüne Synchronisieren-Symbol drücken, die Client-ID
   eintragen und „Spotify verbinden“ wählen.

Für die Handyapp wird kein Spotify Client Secret verwendet oder gespeichert.

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
