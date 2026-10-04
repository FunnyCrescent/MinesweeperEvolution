# MinesweeperEvolution — WebSocket Relay Server

Кроссплатформенный мультиплеер: Android APK + HTML веб-версия играют вместе через этот релей.

## Что делает

- Принимает WebSocket-подключения от Android и HTML клиентов
- Создаёт «комнаты» с 6-символьным кодом (например `K7QXR2`)
- Хост создаёт комнату → даёт код друзьям → они подключаются по коду
- Маршрутизирует сообщения: `STATE`/`START`/`OVER` от хоста всем, `CLICK`/`FLAG`/`CHORD` от клиентов хосту
- Не знает ничего про игру — тупой relay, безопасный

## Запуск локально

```bash
cd server
npm install
PORT=8080 node server.js
```

Откроется WebSocket на `ws://localhost:8080` и health endpoint на `http://localhost:8081/health`.

## Деплой на Render.com (бесплатно)

1. Fork https://github.com/FunnyCrescent/MinesweeperEvolution
2. На https://render.com → New + → Web Service
3. Подключи репозиторий
4. Settings:
   - **Runtime:** Node
   - **Build Command:** `cd server && npm install`
   - **Start Command:** `cd server && node server.js`
   - **Plan:** Free
5. Deploy

Получишь URL вида `wss://minesweeper-evolution.onrender.com`.

⚠️ Free план Render: сервер спит через 15 минут неактивности, просыпается за ~30 сек при первом запросе. Для постоянной игры — переходи на Starter план ($7/мес) или используй Railway.

## Деплой на Railway.app (бесплатно, без сна)

1. Fork репозиторий
2. На https://railway.app → New Project → Deploy from GitHub repo
3. Settings:
   - **Root Directory:** `server`
   - **Build Command:** `npm install`
   - **Start Command:** `node server.js`
4. Deploy

Получишь URL вида `wss://minesweeper-evolution-relay.up.railway.app`.

## Использование в клиентах

### HTML (https://funnycrescent.github.io/MinesweeperEvolution/)

Главное меню → «Подключиться через сервер» → вводишь URL релея + код комнаты хоста.

### Android APK

Главное меню → «Подключиться к серверу» → вводишь URL релея + код комнаты хоста.

## Протокол

JSON-сообщения по WebSocket, по одной строке.

### client → server

| type | fields | action |
|------|--------|--------|
| `CREATE_ROOM` | `nickname` | Создать комнату, стать хостом |
| `JOIN_ROOM` | `roomId`, `nickname` | Подключиться к существующей комнате |
| `BROADCAST` | `payload` | Разослать payload всем в комнате (только хост) |
| `SEND_TO_HOST` | `payload` | Отправить payload только хосту (для клиентов) |
| `LEAVE` | — | Выйти из комнаты |

### server → client

| type | fields | action |
|------|--------|--------|
| `ROOM_CREATED` | `roomId`, `playerId`, `nickname` | Подтверждение создания комнаты |
| `JOIN_ACK` | `nickname`, `playerId` | Подтверждение подключения (ник может быть изменён при дубликате) |
| `LOBBY` | `players[]` | Обновлённый список игроков в лобби |
| `PLAYER_LEFT` | `playerId` | Игрок отключился |
| `ERROR` | `message` | Ошибка |
| (любой payload от хоста) | — | Relay-сообщение: `STATE`, `START`, `OVER`, `GOODBYE`, `CLICK`, `FLAG`, `CHORD` |

## Health check

`GET /health` → `{ok:true, rooms:N, uptime:S}`

## Безопасность

- Сервер НЕ валидирует содержимое payload — он тупой relay
- Любой может создать комнату, любой может подключиться по коду (если знает код)
- Никакой авторизации, токенов и т.д. — для приватной игры с друзьями этого достаточно
- Для публичного релея с защитой от абуза — добавь rate-limit и/или капчу при CREATE_ROOM
