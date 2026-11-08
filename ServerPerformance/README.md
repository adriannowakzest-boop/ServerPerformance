# Server Performance Analyzer

Serwerowy mod Forge dla Minecraft 1.20.1 (Java 17). Zbiera czas trwania ticków i podstawowe metryki JVM/systemu, a następnie zapisuje raport TXT oraz próbki CSV.

## Kompilacja

Wymagany JDK 17 i dostęp do internetu przy pierwszym pobraniu zależności.

```bash
gradle build
```

Gotowy JAR znajdzie się w `build/libs/`.

## Instalacja i komenda

Skopiuj JAR do katalogu `mods` serwera Forge 1.20.1. Komenda `/perfreport` tworzy raport z ostatnich 5 minut, a `/perfreport 600` z ostatnich 600 sekund (zakres 10–1800 s). Wymaga uprawnień operatora poziomu 2.

Raporty trafiają do `performance-reports/`.

## Analiza modów

Od wersji 1.1.0 mod próbkuje stos wątku serwera co 50 ms. Raport TXT zawiera udział rozpoznanych modów w próbkach: 25%+ — wysoki kandydat do dalszej diagnostyki, 10–25% — możliwy kandydat, poniżej 10% — niski udział.

To profilowanie statystyczne, a nie dokładny pomiar czasu CPU. Potwierdzenie wymaga testu A/B na kopii serwera. Klasy transformowane lub ładowane przez niestandardowy classloader mogą nie zostać przypisane.

## Automatyczna kompilacja

GitHub Actions buduje projekt po każdym pushu do `main` i udostępnia JAR jako artifact w zakładce Actions.
