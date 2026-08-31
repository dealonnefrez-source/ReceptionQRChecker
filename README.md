# Reception QR Checker

Prosty system do skanowania QR w punkcie odbioru nagród.

## Składniki

- aplikacja Android dla portierni w folderze `app/`,
- backend API z bazą PostgreSQL (Supabase) w folderze `backend/`,
- QR generowany po zakończeniu gry w UE5.

## Cel

- skanuje kod QR kamerką,
- wysyła kod do backendu,
- pokazuje, czy nagroda może zostać wydana,
- obsługuje przypadki: poprawny, już wykorzystany, nieważny, wygasły.

## Wymagania

- Android Studio
- JDK 17+
- Android SDK 34

## Uruchomienie

1. Otwórz folder `ReceptionQRChecker` w Android Studio.
2. Poczekaj na synchronizację Gradle.
3. Podłącz telefon lub uruchom emulator.
4. Kliknij Run.

## Backend

GitHub może przechowywać kod i workflow do wdrożenia, ale nie jest bazą danych. Do bazy online polecam PostgreSQL na Neon, Supabase albo Render.

Backend uruchamiasz z folderu `backend/`.

```bash
cd backend
python -m pip install -r requirements.txt
set RECEPTIONQR_DATABASE_URL=postgresql://USER:PASSWORD@HOST:5432/DBNAME
python -m uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Adres API dla aplikacji Android ustawiasz w `gradle.properties` przez `receptionQrApiUrl`.

Przykład:

`receptionQrApiUrl=https://twoj-backend.onrender.com/api/qr/redeem`

Najważniejsze endpointy:

- `POST /api/qr/issue` - gra UE5 tworzy nowy QR po zakończeniu rundy,
- `POST /api/qr/redeem` - aplikacja portierni oznacza kod jako wykorzystany,
- `GET /api/qr/{code}` - podgląd danych pojedynczego kodu.

Przykładowy payload z gry:

```json
{
  "player_id": "player_001",
  "points": 50,
  "reward_name": "2 wejścia",
  "match_id": "match_2026_08_31_001"
}
```

Endpoint `POST /api/qr/issue` zwraca kod, który gra powinna zakodować w QR. Portiernia potem wysyła ten kod do `POST /api/qr/redeem`.

W praktyce flow wygląda tak:

1. UE5 kończy grę i wysyła `player_id`, `points`, `reward_name`, `match_id` do `/api/qr/issue`.
2. Backend zapisuje to w bazie online i odsyła kod do QR.
3. Aplikacja portierni skanuje QR i wysyła kod do `/api/qr/redeem`.
4. Backend zwraca `valid` przy pierwszym skanie i `already_used` przy kolejnych.

Przykład odpowiedzi po skanie:

```json
{
  "status": "valid",
  "message": "MOŻNA WYDAĆ NAGRODĘ: 50 pkt"
}
```

Dopuszczalne statusy:
- `valid`
- `already_used`
- `expired`
- `invalid`

## Wdrożenie Online (Render + Supabase)

1. W Supabase utwórz projekt i skopiuj URI z Session Pooler.
2. W Render utwórz nowy Web Service z tego repo.
3. Render automatycznie odczyta `render.yaml` z root projektu.
4. W Render ustaw zmienną środowiskową `RECEPTIONQR_DATABASE_URL` na URI z Supabase.
5. Po deployu wejdź na `https://twoj-backend.onrender.com/health` i potwierdź `database_backend=postgresql`.
6. Ustaw `receptionQrApiUrl` w `gradle.properties` na `https://twoj-backend.onrender.com/api/qr/redeem`.
7. Zbuduj i wgraj aplikację na telefon.
