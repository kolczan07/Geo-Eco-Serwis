# Geo-Eco-Serwis

Natywna aplikacja na Androida (Kotlin, Jetpack Compose).

- **Poczta** – dowolna skrzynka przez IMAP (odbiór) i SMTP (wysyłka): rozmowy, załączniki, wysłane/robocze/kosz, powiadomienia (sprawdzanie co ok. 15 min i po włączeniu ekranu), zaufani nadawcy dla obrazów, podpis. Konto ustawia się w Ustawienia → Konto pocztowe.
- **Iglutech Service** – lista pomp ciepła, szczegóły, alarmy, widżet.
- **Radio internetowe**, **Pogoda**, skróty do aplikacji, widżety.

Wersja bez narzędzi sieciowych, Asaja, synchronizacji z Google Drive i wyboru użytkownika.

## Budowanie

GitHub Actions (`.github/workflows`): push na `main` buduje wersję debug i publikuje ją jako wydanie `debug`; tag `v*` buduje podpisany release.

Wymagane sekrety repozytorium: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Aktualizacje w aplikacji pobiera z wydań tego repozytorium (`kolczan07/Geo-Eco-Serwis`).
