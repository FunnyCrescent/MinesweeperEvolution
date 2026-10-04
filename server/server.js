/**
 * MinesweeperEvolution — WebSocket relay-сервер для кроссплатформенного мультиплеера.
 *
 * Назначение: позволяет Android APK и HTML веб-версии играть вместе.
 * Сервер не знает ничего про игру — он просто маршрутизирует сообщения между
 * клиентами в одной «комнате». Хост-авторитарная архитектура:
 *   - хост создаёт комнату, получает код (например "ABC123")
 *   - клиенты подключаются по этому коду
 *   - хост держит состояние игры, шлёт STATE всем клиентам
 *   - клиенты шлют CLICK/FLAG/CHORD хосту (через сервер)
 *
 * Запуск:
 *   npm install
 *   PORT=8080 node server.js
 *
 * Деплой:
 *   - Render.com: создать Web Service, репо + эту папку, npm install, npm start
 *   - Railway.app: то же самое
 *   - fly.io: flyctl launch
 *   - VPS: systemd unit
 *
 * Протокол сообщений (JSON, по одной строке):
 *   client → server:
 *     {t:"CREATE_ROOM", nickname:"Host"}
 *     {t:"JOIN_ROOM", roomId:"ABC123", nickname:"Alice"}
 *     {t:"BROADCAST", payload:{...}}     — любое сообщение, отправляется всем в комнате
 *     {t:"SEND_TO_HOST", payload:{...}}  — отправить только хосту (например CLICK)
 *     {t:"LEAVE"}
 *   server → client:
 *     {t:"ROOM_CREATED", roomId:"ABC123", playerId:0}
 *     {t:"JOIN_ACK", nickname:"Alice", playerId:1}
 *     {t:"LOBBY", players:[{id:0,name:"Host",isHost:true},...]}
 *     {t:"PLAYER_LEFT", playerId:1}
 *     {t:"ERROR", message:"..."}
 *     ...любое relay-сообщение от хоста (STATE/START/OVER/CLICK/FLAG/CHORD/GOODBYE)
 */
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const MAX_PLAYERS_PER_ROOM = 5;
const ROOM_ID_LENGTH = 6;

const wss = new WebSocket.Server({ port: PORT, clientTracking: true });
console.log(`[relay] WebSocket server listening on :${PORT}`);

/** roomId -> { host: ws, players: Map<playerId, {ws,name,isHost}>, nextId } */
const rooms = new Map();

/** playerId -> roomId, для быстрого поиска при disconnect. */
const playerToRoom = new Map();

function genRoomId() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  // без похожих (0/O, 1/I)
  let id;
  do {
    id = '';
    for (let i = 0; i < ROOM_ID_LENGTH; i++) id += chars[Math.floor(Math.random() * chars.length)];
  } while (rooms.has(id));
  return id;
}

function broadcastToRoom(roomId, message, exceptWs = null) {
  const room = rooms.get(roomId);
  if (!room) return;
  const data = typeof message === 'string' ? message : JSON.stringify(message);
  for (const [, p] of room.players) {
    if (p.ws !== exceptWs && p.ws.readyState === WebSocket.OPEN) {
      p.ws.send(data);
    }
  }
}

function sendTo(roomId, playerId, message) {
  const room = rooms.get(roomId);
  if (!room) return;
  const p = room.players.get(playerId);
  if (!p || p.ws.readyState !== WebSocket.OPEN) return;
  const data = typeof message === 'string' ? message : JSON.stringify(message);
  p.ws.send(data);
}

function sendToHost(roomId, message) {
  const room = rooms.get(roomId);
  if (!room || !room.host) return;
  if (room.host.readyState !== WebSocket.OPEN) return;
  const data = typeof message === 'string' ? message : JSON.stringify(message);
  room.host.send(data);
}

function rebuildLobby(roomId) {
  const room = rooms.get(roomId);
  if (!room) return;
  const players = [];
  for (const [id, p] of room.players) {
    players.push({ id, name: p.name, isHost: p.isHost });
  }
  broadcastToRoom(roomId, { t: 'LOBBY', players });
}

function deduplicateNickname(name, existing) {
  const base = (name || 'Игрок').trim().slice(0, 20) || 'Игрок';
  const taken = new Set(existing.map(n => n.toLowerCase()));
  if (!taken.has(base.toLowerCase())) return base;
  let i = 2;
  while (true) {
    const candidate = `${base} (${i})`;
    if (!taken.has(candidate.toLowerCase())) return candidate;
    i++;
  }
}

function removePlayer(ws) {
  const roomId = playerToRoom.get(ws);
  if (!roomId) return;
  const room = rooms.get(roomId);
  if (!room) { playerToRoom.delete(ws); return; }

  // Найти игрока по ws
  let removedId = null;
  for (const [pid, p] of room.players) {
    if (p.ws === ws) { removedId = pid; break; }
  }
  if (removedId === null) { playerToRoom.delete(ws); return; }

  room.players.delete(removedId);
  playerToRoom.delete(ws);

  // Если вышел хост — закрыть комнату, уведомить остальных
  if (room.host === ws) {
    broadcastToRoom(roomId, { t: 'GOODBYE' }, ws);
    for (const [, p] of room.players) {
      try { p.ws.close(); } catch (e) {}
    }
    rooms.delete(roomId);
    console.log(`[relay] room ${roomId} closed (host left)`);
    return;
  }

  // Иначе — уведомить остальных
  broadcastToRoom(roomId, { t: 'PLAYER_LEFT', playerId: removedId }, ws);
  rebuildLobby(roomId);
  console.log(`[relay] player ${removedId} left room ${roomId}, ${room.players.size} remaining`);
}

wss.on('connection', (ws, req) => {
  const ip = req.socket.remoteAddress;
  console.log(`[relay] connection from ${ip}`);

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch (e) { return; }  // malformed JSON — игнорируем

    if (!msg || !msg.t) return;

    switch (msg.t) {
      case 'CREATE_ROOM': {
        if (playerToRoom.has(ws)) {
          ws.send(JSON.stringify({ t: 'ERROR', message: 'Ты уже в комнате' }));
          return;
        }
        const roomId = genRoomId();
        const name = (msg.nickname || 'Хост').trim().slice(0, 20) || 'Хост';
        const room = {
          host: ws,
          players: new Map([[0, { ws, name, isHost: true }]]),
          nextId: 1,
        };
        rooms.set(roomId, room);
        playerToRoom.set(ws, roomId);
        ws.send(JSON.stringify({ t: 'ROOM_CREATED', roomId, playerId: 0, nickname: name }));
        rebuildLobby(roomId);
        console.log(`[relay] room ${roomId} created by ${name}`);
        break;
      }
      case 'JOIN_ROOM': {
        if (playerToRoom.has(ws)) {
          ws.send(JSON.stringify({ t: 'ERROR', message: 'Ты уже в комнате' }));
          return;
        }
        const roomId = (msg.roomId || '').toString().toUpperCase();
        const room = rooms.get(roomId);
        if (!room) {
          ws.send(JSON.stringify({ t: 'ERROR', message: 'Комната не найдена' }));
          return;
        }
        if (room.players.size >= MAX_PLAYERS_PER_ROOM) {
          ws.send(JSON.stringify({ t: 'ERROR', message: 'Лобби заполнено (макс. ' + MAX_PLAYERS_PER_ROOM + ')' }));
          return;
        }
        const existing = [...room.players.values()].map(p => p.name);
        const finalName = deduplicateNickname(msg.nickname, existing);
        const playerId = room.nextId++;
        room.players.set(playerId, { ws, name: finalName, isHost: false });
        playerToRoom.set(ws, roomId);
        ws.send(JSON.stringify({ t: 'JOIN_ACK', nickname: finalName, playerId }));
        rebuildLobby(roomId);
        console.log(`[relay] ${finalName} joined room ${roomId} as #${playerId}`);
        break;
      }
      case 'BROADCAST': {
        // Шлём payload всем в комнате (включая отправителя — пусть тоже видит свой эхо, не страшно)
        const roomId = playerToRoom.get(ws);
        if (!roomId) return;
        const room = rooms.get(roomId);
        if (!room) return;
        // Только хост может слать STATE/START/OVER
        if (room.host !== ws) {
          // Клиенты могут слать только LOBBY-обновления — но мы это делаем на сервере.
          // Игнорируем.
          return;
        }
        broadcastToRoom(roomId, msg.payload || {});
        break;
      }
      case 'SEND_TO_HOST': {
        // Клиент → хост (например, CLICK/FLAG/CHORD)
        const roomId = playerToRoom.get(ws);
        if (!roomId) return;
        const room = rooms.get(roomId);
        if (!room) return;
        if (room.host === ws) return;  // хосту не надо слать самому себе
        // Находим playerId отправителя
        let senderId = -1;
        for (const [pid, p] of room.players) {
          if (p.ws === ws) { senderId = pid; break; }
        }
        if (senderId < 0) return;
        // Добавляем senderId в payload, чтобы хост знал, кто прислал
        const payload = Object.assign({}, msg.payload || {}, { senderId });
        sendToHost(roomId, payload);
        break;
      }
      case 'LEAVE': {
        removePlayer(ws);
        try { ws.close(); } catch (e) {}
        break;
      }
      default:
        // Неизвестное — игнорируем
        break;
    }
  });

  ws.on('close', () => {
    removePlayer(ws);
    console.log(`[relay] ${ip} disconnected`);
  });

  ws.on('error', () => {
    removePlayer(ws);
  });
});

// Раз в минуту чистим пустые комнаты
setInterval(() => {
  for (const [roomId, room] of rooms) {
    if (room.players.size === 0) {
      rooms.delete(roomId);
      console.log(`[relay] cleaned empty room ${roomId}`);
    }
  }
}, 60_000);

// Health endpoint (HTTP GET /health) — для Render/Railway uptime checks
const http = require('http');
const healthServer = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size, uptime: process.uptime() }));
  } else {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('MinesweeperEvolution relay. WebSocket endpoint: ws://' + req.headers.host + '/');
  }
});
const HTTP_PORT = process.env.HTTP_PORT || (PORT == 8080 ? 8081 : 8080);
healthServer.listen(HTTP_PORT, () => {
  console.log(`[relay] health endpoint on :${HTTP_PORT}/health`);
});

process.on('SIGTERM', () => {
  console.log('[relay] SIGTERM, shutting down');
  wss.close();
  healthServer.close();
  process.exit(0);
});
