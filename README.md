# VideoCatcher 2.0

Android-App: eine Webseite öffnen, das Video abspielen, die Videodatei herunterladen.
Mehrere Downloads laufen gleichzeitig und im Hintergrund weiter.

## Installieren (ohne PC)

1. Auf GitHub rechts auf **Releases** tippen.
2. Beim neuesten Eintrag `VideoCatcher-2.0.apk` antippen und herunterladen.
3. Datei öffnen → «Unbekannte Apps installieren» für den Browser erlauben → Installieren.

Jede Änderung im Branch `main` baut automatisch eine neue APK (Reiter **Actions**).
Alle Builds haben dieselbe Signatur, ein Update lässt sich also einfach darüber installieren.

Die alte Version 1 hat einen anderen Paketnamen (`com.example.videocatcher`) und kann
gelöscht werden.

## Bedienung

**Browser**
- Adresse eingeben, **Los** antippen. Video auf der Seite abspielen.
- Unten erscheint der gelbe Knopf **Video gefunden** → antippen → **Laden**.
  Zu jedem Eintrag steht Grösse oder Länge; bei mehreren ist meist der grösste der richtige.
- **Pop-ups** öffnen sich in einer eigenen Ebene mit gelber Leiste. **Schliessen** oder die
  Zurück-Taste bringt dich zur Seite dahinter – sie wird nicht neu geladen, das Video läuft weiter.
  In der Leiste oder im Menü ⋮ lassen sich Pop-ups ganz blockieren.
- Der Wechsel zu **Downloads** und zurück lädt die Seite nicht neu.
- Zurück-Taste: Vollbild verlassen → Downloads schliessen → Pop-up schliessen → eine Seite zurück.

**Downloads**
- Fortschrittsbalken, Prozent, Grösse, Geschwindigkeit; Pause / Weiter / Nochmal / Löschen.
- Bis zu 3 gleichzeitig, weitere warten. Läuft mit Benachrichtigung im Hintergrund weiter.
- Bricht die Verbindung ab, wird von selbst an der gleichen Stelle fortgesetzt.
- Fertige Videos liegen unter `Movies/VideoCatcher` (Ton unter `Music/VideoCatcher`) und
  erscheinen in der Galerie. Streams werden nach dem Laden in MP4 umgepackt.
- Oben lässt sich ein direkter Video-Link einfügen.

## Was nicht geht

- Verschlüsselte Streams und DRM (Netflix, Disney+ usw.) – bewusst nicht.
- Livestreams, MPEG-DASH (`.mpd`).
- Seiten, die das Video stückweise per Skript zusammensetzen, ohne Playlist: dort wird nichts gefunden.
- Streams mit getrennter Tonspur: die Datei kann ohne Ton sein (wird beim Download angezeigt).

Ob ein Video heruntergeladen werden darf, hängt vom Urheberrecht und den Bedingungen der Seite ab.

## Aufbau

```
app/src/main/java/win/wintertools/videocatcher/
  MainActivity.kt
  browser/BrowserController.kt   WebViews, Pop-up-Ebene, Vollbild, Verlauf
  browser/MediaSniffer.kt        erkennt Videos unter den Anfragen der Seite
  net/FileDownloader.kt          direkte Dateien, Fortsetzen per HTTP-Range
  net/HlsDownloader.kt           m3u8-Streams, Fortsetzen pro Segment
  net/Remuxer.kt                 .ts → .mp4 ohne Neukodierung
  service/DownloadService.kt     Hintergrund-Dienst, Warteschlange
  data/                          Downloadliste (JSON-Datei), Aktionen
  ui/                            Oberfläche (Compose), kontrastreiche Farben in Theme.kt
app/src/test/                    Tests der Download-Logik gegen einen lokalen Testserver
```

Bauen am PC: `./gradlew assembleDebug` (JDK 17, Android SDK 35).

Der Signaturschlüssel in `keystore/` liegt absichtlich im Repo, damit jeder Build dieselbe
Signatur hat. Er schützt nichts – für eine Veröffentlichung im Play Store bräuchte es einen geheimen.
