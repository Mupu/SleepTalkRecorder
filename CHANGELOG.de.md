# Changelog

Was sich in jeder Version für die Nutzer geändert hat. Die App zeigt diese Datei unter „Neuigkeiten“.
Format: [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionen: [SemVer](https://semver.org/lang/de/).

## [0.2.1] - 2026-11-10

### Behoben

- Beschriftungen, die nicht passten, zerfallen nicht mehr und werden nicht mehr abgeschnitten: „Keine Sprache“ unter einem Transkript, die Auswahl, wann die Spracherkennung läuft, das 12-Stunden-Format, der gezeigte Ausschnitt der Zeitleiste und Zeitspannen in der Statistik passen jetzt, auch bei größerer Schrift.
- Das Diagramm der gesprochenen Zeit pro Nacht heißt „Gesprochene Zeit pro Nacht“, weil es auch Sekunden zeigt.

## [0.2.0] - 2026-11-10

### Geändert

- Die Zeitleiste eines Schlafs bewegt sich wie eine Karte: Zieh zwei Finger auseinander oder zusammen, um um den Punkt zwischen ihnen zu zoomen, und verschiebe sie dabei zugleich; wische und lass los, damit sie weitergleitet; tippe doppelt, um an dieser Stelle hineinzuzoomen.

## [0.1.1] - 2026-11-10

### Behoben

- Auf Handys mit DuraSpeed sagt die Einrichtung jetzt, dass du in den Einstellungen nach „DuraSpeed“ suchen sollst, weil die App diese Seite nicht direkt öffnen kann.

## [0.1.0] - 2026-11-09

Die erste Beta: Die App nimmt deine Nächte auf und lässt dich nur das hören, was gesagt wurde.

### Hinzugefügt

- Nimm eine ganze Nacht oder einen Mittagsschlaf im Hintergrund bei ausgeschaltetem Bildschirm auf und stoppe über die Benachrichtigung oder automatisch zu einer spätesten Uhrzeit, die du wählst.
- Jeder Schlaf steht als Nacht oder Mittagsschlaf in der Bibliothek; du wählst, wann Nacht und Mittagsschlaf beginnen, und eine neue Aufnahme in derselben Nacht setzt sie fort.
- Spiele einen Schlaf vollständig ab oder nur seine Ereignisse, mit Vorlauf, Nachlauf und sanften Übergängen. Jede Stelle zeigt die Zeit in der Aufnahme und die Uhrzeit, zu der sie geschah.
- Nach dem Stopp findet die App die Ereignisse der Nacht und zeigt sie auf einer Zeitleiste ihrer Lautstärke, die du zoomen und verschieben kannst.
- Die Spracherkennung auf dem Handy schreibt auf, was gesagt wurde, und markiert Sprachereignisse; auch ein Murmeln ohne Wörter wird markiert. Lade die Modelle in den Einstellungen herunter, korrigiere einen Text, markiere ein Ereignis als keine Sprache oder lass die Erkennung mit einem anderen Modell erneut laufen.
- Stelle die Suche für einen ganzen Schlaf oder rund um ein einzelnes Ereignis empfindlicher oder unempfindlicher ein und sieh die gefundenen Ereignisse, während du den Regler bewegst.
- Blende Ereignisse aus, die du nicht hören willst, und sieh dir die ausgeblendeten noch einmal an.
- Statistik eines Schlafs und über alle Nächte: wie viele Ereignisse, wie lange und wann du gesprochen hast, im Vergleich mit deinen anderen Nächten.
- Lösche einen Schlaf durch langes Drücken, oder lass die App Schlafzeiten nach einer Anzahl von Tagen löschen.
- Eine Einrichtung beim ersten Start fragt nach der Sprache und führt dich durch die Berechtigungen, die eine ganze Nacht braucht, auch durch die zusätzlichen Schritte mancher Handyhersteller; der Startbildschirm warnt, wenn eine fehlt.
- Eine Aufnahme, die ein Absturz oder ein leerer Akku abgebrochen hat, wird repariert und bleibt erhalten; Lücken und Abschnitte, in denen eine andere App das Mikrofon hatte, werden markiert.
- Wähle das Design, die Sprache (Deutsch oder Englisch), das Uhrzeitformat sowie Vorlauf und Nachlauf der Ereignisse.
- Nach einem Update zeigt die App einmal, was neu ist; die Einstellungen listen unter „Neuigkeiten“ jedes Update auf.
- Die App hält sich selbst über ihre Releases auf GitHub aktuell: Sie sucht bei jedem Start nach einer neuen Version, lädt sie im WLAN herunter (über mobile Daten nach einem Tippen) und installiert sie, wenn du auf „Aktualisieren“ tippst, oder von selbst, sobald sie im Hintergrund ist und nichts aufnimmt oder abspielt. Ein Update unterbricht nie eine Aufnahme und wird nur installiert, wenn es wie die installierte App signiert ist.
- Alles bleibt auf dem Handy: Es gibt kein Konto, und das Internet wird nur zum Herunterladen der Spracherkennungsmodelle und der Updates der App genutzt.
