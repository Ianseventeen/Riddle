# Riddles

A real-time multiplayer logic game for phones. One player creates a room, the others join with a QR code or a 5-letter code, and everyone races to solve the same matrix and number-sequence puzzles. The first correct answer takes the point.

![Java](https://img.shields.io/badge/Java-25-black?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-black?style=for-the-badge&logo=springboot)
![WebSocket](https://img.shields.io/badge/WebSocket-realtime-black?style=for-the-badge)

## How it plays

- **2 to 10 players per room**, each on their own phone. No app install and no account.
- **Two levels:** Normal (60 s match) and Universitário (90 s match), 40 puzzles each.
- **Two puzzle types:** a 3×3 matrix with a missing cell, and a number sequence with a missing term.
- **First correct answer scores.** A wrong answer locks that player out of the current puzzle, so guessing has a cost. If everyone is locked, the answer is revealed with no winner.
- **Every reveal shows the rule** behind the puzzle, so players learn the pattern.
- **Final podium** with ties sharing a position, and a one-tap rematch.

## Built for phones

- **Join by QR code:** the lobby shows a QR code with the room link, and the Entrar screen has a camera button that scans it (native `BarcodeDetector`, with a `jsQR` fallback).
- **Share invite** through the phone's native share sheet.
- **Automatic reconnection:** if the network drops or the page reloads, the player returns to the same room with the same score (session token with a 30 s grace period).
- **Screen stays on** during a match (Wake Lock API), and the phone vibrates on right and wrong answers.
- **Fair timer:** the client corrects its clock against the server's, so every phone shows the same countdown.

## Architecture

```
phone ── WebSocket (/ws, JSON) ──► Handler ──► GameRoom (one per room)
                                                  ├── QuestionBank (questions.json)
                                                  └── Scheduler (reveal and match timers)
```

- **Backend:** Java 25, Spring Boot 4 (Web MVC + WebSocket), Gson. All state is in memory; no database.
- **Game logic is isolated and testable:** `GameRoom` talks to the outside only through `Listener` and `Scheduler` interfaces, so tests drive it with a fake clock. A generation counter makes timers from a previous round harmless.
- **The server is the authority:** options are shuffled per puzzle, and the answer and rule are sent only at the reveal, so they can't be read from the client.
- **Frontend:** a single-page app in plain HTML, CSS and ES modules, served by Spring from `src/main/resources/static`. Cinzel for titles and numbers, Inter for text.

### WebSocket protocol

| Client → server | Server → client |
| :--- | :--- |
| `CREATE {nickname, level}` | `JOINED {roomCode, playerId, token}` |
| `JOIN {room, nickname}` | `ROOM {players, hostId, state}` |
| `RESUME {room, token}` | `QUESTION {question, endsAt, serverNow}` |
| `START` | `WRONG`, `LOCKED` |
| `ANSWER {questionId, value}` | `REVEAL {answer, rule, winnerId, players}` |
| `REMATCH`, `LEAVE`, `PING` | `GAME_OVER {ranking}`, `ERROR {code, message}`, `PONG` |

## Running locally

Requires JDK 25.

```bash
./gradlew bootRun        # Windows: .\gradlew.bat bootRun
```

Open `http://<your-pc-ip>:8080` on the computer (not `localhost`, so the QR code points to an address phones on the same Wi-Fi can reach). The in-app camera scanner needs HTTPS, so over a local network use the phone's own camera app on the QR code or type the room code.

## Tests

```bash
./gradlew test
```

31 tests: the question bank, and the full room lifecycle (joining, the 10-player limit, host transfer, first-correct-answer scoring, lockouts, timers, ties, rematch, reconnection and its expiry).

## Deploy

The repository has a `Dockerfile` (Temurin 25) and deploys to Render as a web service. The port comes from the `PORT` environment variable (default 8080).
