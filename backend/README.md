# Reception QR API

Backend dla aplikacji portierni i gry UE5 z bazą online.

## Co robi

- przyjmuje dane po zakończeniu gry,
- generuje kod QR dla sesji gracza,
- zapisuje punkty w bazie online,
- pozwala portierowi zeskanować kod tylko raz,
- zwraca status `valid`, `already_used` albo `invalid`.

## Uruchomienie

```bash
cd backend
python -m pip install -r requirements.txt
set RECEPTIONQR_DATABASE_URL=postgresql://USER:PASSWORD@HOST:5432/DBNAME
python -m uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Jeśli nie ustawisz `RECEPTIONQR_DATABASE_URL`, backend uruchomi się na lokalnym SQLite tylko do testów.

## Endpointy

### `POST /api/qr/issue`

Tworzy nowy kod QR po zakończeniu gry.

Przykład:

```json
{
  "player_id": "player_001",
  "points": 50,
  "reward_name": "2 wejścia",
  "match_id": "match_2026_08_31_001"
}
```

Odpowiedź:

```json
{
  "code": "f3d0d3f8b8f24803a0c73f1d0ed3af21",
  "qr_payload": "f3d0d3f8b8f24803a0c73f1d0ed3af21",
  "player_id": "player_001",
  "points": 50,
  "reward_name": "2 wejścia",
  "match_id": "match_2026_08_31_001",
  "issued_at": "2026-08-31T12:00:00+00:00"
}
```

### `POST /api/qr/redeem`

Sprawdza kod po skanie i oznacza go jako użyty.

Przykład:

```json
{
  "code": "f3d0d3f8b8f24803a0c73f1d0ed3af21",
  "staff_id": "recepcja_1"
}
```

## Baza danych

W produkcji ustaw `RECEPTIONQR_DATABASE_URL` na PostgreSQL, np. z Neon, Supabase albo Render.
Jeśli chcesz testować lokalnie, możesz zostawić SQLite jako fallback.

## Deployment Render

1. Wypchnij repo na GitHub.
2. W Render utwórz Web Service z tego repo.
3. Upewnij się, że Render używa pliku `render.yaml` z root projektu.
4. W Render dodaj env var `RECEPTIONQR_DATABASE_URL` z URI Session Pooler z Supabase.
5. Zdeployuj i sprawdź endpoint `/health`.

Oczekiwany wynik health:

- `status`: `ok`
- `database_backend`: `postgresql`