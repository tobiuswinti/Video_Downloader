# VideoCatcher 2.2

Android-App: eine Webseite öffnen, das Video abspielen, die Videodatei herunterladen.
Mehrere Downloads laufen gleichzeitig und im Hintergrund weiter.

## Installieren (ohne PC)

1. Auf GitHub rechts auf **Releases** tippen.
2. Beim neuesten Eintrag `VideoCatcher-2.2.apk` antippen und herunterladen.
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
- Streams werden schon **während des Ladens** in MP4 umgepackt (ein Hintergrund-Faden liest die
  wachsende Datei als einen durchgehenden Strom). Am Ende bleibt nur ein kurzer Abschluss.
- Sicherung: Die MP4 wird nur übernommen, wenn ihre Länge zur Länge des Streams passt und der Ton
  so lang ist wie das Bild. Sonst wird nach dem Laden umgewandelt; scheitert auch das, bleibt die
  ursprüngliche `.ts`-Datei erhalten (der eingebaute Player spielt sie ab).
- Fertige Videos erscheinen in der Galerie, sortiert in Ordner:
  `Movies/VideoCatcher/<Genre>/` und `Movies/VideoCatcher/Serien/<Serie>/Staffel 1/`.
- Oben lässt sich ein direkter Video-Link einfügen.

**Mediathek**
- Regale wie in einer Filmothek: Weiterschauen, Neu, Serien, dann ein Regal pro Genre. Suche oben.
- Vorschaubilder stammen aus dem Video selbst.
- Folgen einer Serie stehen zusammen auf einer Serienseite, nach Staffel und Folge geordnet.
  Erkannt wird das aus dem Titel (`S01E02`, `1x02`, `Staffel 1 Folge 2`, `Folge 5`).
- **Genre / Serie bearbeiten**: Titel, Film oder Serie, Genre, Staffel, Folge. Das Genre gilt für die
  ganze Serie. Eigene Dateien wandern dabei in den passenden Ordner (ab Android 11).
- **＋ Video hinzufügen**: vorhandene MP4 und andere Videos aufnehmen. Sie werden nicht kopiert und
  beim Entfernen aus der Mediathek nie gelöscht.
- Eingebauter Player: merkt sich die Stelle, spielt bei Serien die nächste Folge weiter.

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
  net/LiveRemuxer.kt             .ts → .mp4 während des Ladens
  net/Remuxer.kt                 Ausweichweg: .ts → .mp4 nach dem Laden
  data/TitleParser.kt            erkennt Serie, Staffel, Folge aus dem Titel
  ui/LibraryScreen.kt            Mediathek (Regale, Serienseite, Einordnen)
  ui/PlayerScreen.kt             eingebauter Player
  util/Thumbs.kt                 Vorschaubilder aus dem Video
  service/DownloadService.kt     Hintergrund-Dienst, Warteschlange
  data/                          Downloadliste (JSON-Datei), Aktionen
  ui/                            Oberfläche (Compose), kontrastreiche Farben in Theme.kt
app/src/test/                    Tests der Download-Logik gegen einen lokalen Testserver
```

Bauen am PC: `./gradlew assembleDebug` (JDK 17, Android SDK 35).

Der Signaturschlüssel in `keystore/` liegt absichtlich im Repo, damit jeder Build dieselbe
Signatur hat. Er schützt nichts – für eine Veröffentlichung im Play Store bräuchte es einen geheimen.
