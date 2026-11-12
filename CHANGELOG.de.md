# Changelog

Was sich in jeder Version für die Nutzer geändert hat. Die App zeigt diese Datei unter „Neuigkeiten“.
Format: [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionen: [SemVer](https://semver.org/lang/de/).

## [0.11.0] - 2026-11-12

### Hinzugefügt

- Sortiere Ereignisse mit Tags. Der Tag-Knopf zwischen dem Abspielknopf eines Ereignisses und seinem ⋮-Menü, oder in der Leiste mehrerer ausgewählter Ereignisse, öffnet die Tags des Ereignisses: Favorit und Tags, die du selbst anlegst, angehakt, leer oder mit Strich, wenn nur manche der ausgewählten Ereignisse ihn haben. Das Fenster öffnet mit deinen letzten Änderungen angehakt, sodass du das nächste Ereignis mit dem Knopf und OK genauso taggst. Der Filterknopf über den Ereignissen wählt beliebige deiner Tags und der automatischen (Sprache, Ereignis, Murmeln, Benannt, Ausgeblendet); die gewählten erscheinen als Pillen, die ein Tippen entfernt, die Liste zeigt die Ereignisse mit einem davon, und „Alle“ zeigt wieder jedes Ereignis. Deine Favoriten wurden zum Tag Favorit und behalten ihre Namen, die ein Ereignis nicht mehr zum Favoriten machen. Der Tab „Getaggt“ der Bibliothek listet jedes Ereignis mit einem deiner Tags oder einem Namen, mit demselben Filter. „Getaggte Schlafzeiten behalten“ beim automatischen Löschen behält solche Schlafzeiten, wie bisher die mit Favoriten.

## [0.10.1] - 2026-11-11

### Behoben

- Ein Ereignis, dessen Transkript nur eine Geräuschmarke ohne schließende Klammer ist, etwa „(door“ oder „(engine“, zählt nicht mehr als Sprache und erscheint nicht mehr unter „Nur Sprache“. Schon erkannte Nächte werden nach dem Update von selbst korrigiert, ohne sie neu zu erkennen.

## [0.10.0] - 2026-11-11

### Hinzugefügt

- Ein Schlaf zeigt, wie lange seine Ereignisse zusammen dauern, wie in der Bibliothek: oben neben seiner Zeit und Dauer für alle seine Ereignisse („630 Ereignisse · 1 h“) und unter den Filterknöpfen für die Ereignisse, die der gewählte Filter listet („In der Liste: 42 Ereignisse · 3 min 10 s“). Beide folgen jedem Ereignis, das du ausblendest, wieder einblendest oder als Favorit behältst.

## [0.9.0] - 2026-11-11

### Geändert

- Mehrere zusammen geteilte Ereignisse sind nummeriert: Jede Zeile des mit Videos mitgeschickten Texts beginnt mit ihrer Nummer, und die Karte jedes Videos und der Name jeder Datei tragen dieselbe Nummer, sodass du siehst, welche Zeile zu welchem Video gehört. Die Karten der Videos haben außerdem die Form der Kacheln, in denen WhatsApp sie als Album zeigt, sodass Name, Uhrzeit und Transkript nicht mehr abgeschnitten werden. Ein einzeln geteiltes Ereignis sieht aus wie bisher.

## [0.8.0] - 2026-11-11

### Hinzugefügt

- Halte in der Bibliothek im Tab Favoriten einen Favoriten gedrückt, um ihn auszuwählen, und tippe weitere Favoriten an, um sie hinzuzufügen, auch aus verschiedenen Schlafzeiten. Die Leiste oben zeigt, wie viele du ausgewählt hast und wie lange sie zusammen dauern, und teilt sie oder entfernt sie aus den Favoriten, was du rückgängig machen kannst; Zurück beendet die Auswahl. Zusammen geteilt kommt jeder Favorit in eine eigene Datei, jeweils aus seinem eigenen Schlaf geschnitten, und du bleibst in der Bibliothek.

## [0.7.1] - 2026-11-11

### Behoben

- Einen Favoriten aus der Bibliothek zu teilen öffnet den Schritt zum Teilen direkt dort statt in seinem Schlaf, sodass du nach dem Teilen oder Schließen in der Bibliothek bleibst.

## [0.7.0] - 2026-11-11

### Hinzugefügt

- „Neu erkennen mit …“ im ⋮-Menü eines Ereignisses erkennt nur dieses Ereignis mit einem anderen installierten Modell neu, in Sekunden statt einer ganzen Nacht. Das geöffnete Transkript zeigt dann das Ergebnis jedes Modells mit seinem Namen, das des aktiven Modells zuerst. Beim Korrigieren des Texts kannst du das Ergebnis eines Modells übernehmen oder löschen; sobald du eigenen Text schreibst, wird nur noch dieser gezeigt.
- Halte ein Ereignis in der Liste eines Schlafs gedrückt, um es auszuwählen, und tippe weitere an, um sie dazuzunehmen. Die Leiste oben zeigt, wie viele du ausgewählt hast und wie lange sie zusammen dauern, und teilt sie, macht sie zu Favoriten oder blendet sie alle auf einmal aus; Zurück beendet die Auswahl. Zusammen geteilt kommt jedes Ereignis in eine eigene Video- oder Audiodatei, mit einem Vorlauf und Nachlauf, den du für alle wählst, und der mitgeschickte Text hat für jedes eine Zeile.
- Die Spracherkennung eines Schlafs lässt sich pausieren und fortsetzen, um Akku zu sparen: mit „Pausieren“ in ihrem Pop-up hinter dem Statussymbol auf der Schlafseite, im ⋮-Menü des Schlafs oder in der Benachrichtigung, während sie läuft. Das Pop-up zeigt dann, wie viele Ereignisse übrig sind. Die Pause hält, bis die Spracherkennung wieder von selbst starten würde (nach deiner nächsten Aufnahme oder beim nächsten Laden, wenn sie nur beim Laden läuft) oder bis du sie fortsetzt, auch nachdem die App geschlossen war; dann geht sie dort weiter, wo sie aufgehört hat.

### Geändert

- Ein Ereignis zu teilen schickt jetzt standardmäßig ein kurzes Video: eine Karte mit dem Namen des Favoriten oder sonst der Art des Ereignisses, Datum, Uhrzeit und Transkript, dazu der Ton des Ereignisses. WhatsApp und andere Messenger zeigen den Text, der mit einem Video kommt, verwerfen ihn aber bei einer Audiodatei. Häkchen legen fest, was dieser Text nennt: Name, Datum und Uhrzeit, Transkript, Schlaf und Länge; die App merkt sich deine Wahl fürs nächste Teilen. „Audio“ schickt weiterhin nur den Ton.

## [0.6.1] - 2026-11-11

### Behoben

- Die App wird nicht mehr dauerhaft schwarz, wenn du direkt nach dem Zurückgehen von einem anderen Bildschirm auf „Bibliothek“ tippst. Ein Tippen auf den Bildschirm, der sich gerade schließt, geht nicht mehr ein zweites Mal zurück.

## [0.6.0] - 2026-11-11

### Hinzugefügt

- Knöpfe links und rechts vom Abspielknopf springen zum vorherigen und zum nächsten Ereignis der Liste, auch mit einem Filter, und spielen weiter oder bleiben pausiert. So kommst du zu einem Ereignis zurück, um es als Favorit zu markieren, wenn schon das nächste läuft.

## [0.5.1] - 2026-11-10

### Behoben

- Findet „Jetzt suchen“ in den Einstellungen eine neuere Version, bieten die Einstellungen ihr Laden und „Aktualisieren“ direkt dort an, mit demselben Fortschritt wie die Startseite, statt nur auf der Startseite.

## [0.5.0] - 2026-11-10

### Hinzugefügt

- „Teilen“ im ⋮-Menü eines Ereignisses und ein Teilen-Knopf neben jedem Favoriten schicken das Ereignis als Audiodatei an WhatsApp, Telegram, E-Mail oder jede andere App. Vor dem Teilen kannst du Anfang und Ende verschieben und es dir anhören; zu Beginn ist es das Ereignis mit Vorlauf und Nachlauf, wie beim geschnittenen Abspielen.

## [0.4.2] - 2026-11-10

### Geändert

- „Nur Ereignisse“ beginnt standardmäßig 1 s vor jedem Ereignis statt 1,5 s. Einen selbst eingestellten Vorlauf behält die App.

## [0.4.1] - 2026-11-10

### Behoben

- „Für Analyse exportieren“ zeigt seinen Fortschritt, sagt vorher, wie viel es kopieren wird, und kopiert nur noch, was sich seit dem letzten Export geändert hat, statt alles erneut.

## [0.4.0] - 2026-11-10

### Hinzugefügt

- „Für Analyse exportieren“ in den Einstellungen kopiert alle Schlafzeiten mit Ton in den Ordner der App auf dem Handy-Speicher, wo ein PC sie über USB liest. „Löschen“ gibt den Platz wieder frei.

## [0.3.1] - 2026-11-10

### Geändert

- Ein Tipp auf einen Favoriten in der Bibliothek öffnet seinen Schlaf an dieser Stelle, ohne abzuspielen; sein Play-Knopf öffnet ihn und spielt ihn ab.

## [0.3.0] - 2026-11-10

### Hinzugefügt

- Behalte Ereignisse als Favoriten und gib ihnen im ⋮-Menü einen Namen. Ein Stern markiert sie, der Filter „Favoriten“ zeigt nur sie, und der neue Reiter „Favoriten“ in der Bibliothek zeigt die Favoriten aller Schlafzeiten; tippe einen an, um ihn in seinem Schlaf zu hören. Das automatische Löschen behält Schlafzeiten mit Favoriten.
- Markiere im ⋮-Menü jedes Ereignis als Sprache, wenn die Erkennung Sprache oder ein Murmeln übersehen hat; mit „Text korrigieren“ kannst du dann die Wörter eintragen. Bei einem Ereignis, das du als keine Sprache markiert hast, holt derselbe Punkt zurück, was die Erkennung gefunden hatte.

### Geändert

- Die Aktionen eines Ereignisses gibt es nur noch in seinem ⋮-Menü; ein aufgeklapptes Transkript wiederholt sie nicht mehr als Knöpfe.
- „Vollständig“ und „Nur Ereignisse“ zeigen keine Symbole mehr, damit die Beschriftung neben dem Tempo-Knopf Platz hat.

## [0.2.2] - 2026-11-10

### Behoben

- Im Empfindlichkeits-Fenster zerfällt „Übernehmen“ nicht mehr: Passen die Knöpfe nicht in eine Zeile, rutschen „Zurücksetzen“ und „Übernehmen“ unter „Anhören“. Die Legende bricht nur zwischen ganzen Einträgen um, und die Uhrzeiten unter dem Diagramm wiederholen sich nicht mehr und überlappen nicht mehr.

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
