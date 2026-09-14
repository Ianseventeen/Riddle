# Riddles 🧩 - Interactive Math Challenge & Multiplayer Game

An interactive, gamified math riddle application designed for big-screen displays (TV / Notebook) controlled seamlessly via smartphones using QR Codes.

![Riddles Banner](https://img.shields.io/badge/Riddles-Math%20Game-black?style=for-the-badge&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4+-brightgreen?style=for-the-badge&logo=springboot)
![SQLite](https://img.shields.io/badge/SQLite-Database-blue?style=for-the-badge&logo=sqlite)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-orange?style=for-the-badge)

---

## 🌟 Highlights & Features

- 📺 **Big-Screen Host View (`tv.html`)**: Clean TV display with 60-second countdown timer, real-time scoreboard, progress tracking, and instant leaderboard podium.
- 📱 **QR Code Mobile Controller (`player.html`)**: Zero app installation required! Players scan the on-screen QR code to turn their mobile phone into a responsive touch controller with a numeric keypad.
- ⚡ **Single Player Mode**: Speed challenge where players try to solve as many math riddles as possible within 60 seconds. At the end of the round, players enter their Name and Email to save their score to the Hall of Fame.
- ⚔️ **Multiplayer Mode (1v1 Dueling)**: Two players scan a single QR Code from their phones to get assigned as **Player 1 (Blue)** and **Player 2 (Red)**. Both players see the same question on TV and attempt to answer simultaneously. The first player to answer correctly wins the point!
- 💾 **SQLite Leaderboard Database**: Automatically stores player scores, names, emails, and dates in an embedded `riddles.db` database for easy retrieval and manual certificate issuance for Top 1, Top 2, and Top 3 winners.
- 🌐 **Automatic LAN IP Resolution**: Automatically resolves local Wi-Fi IP addresses so QR codes work instantly across any mobile device connected to the same network.
- 🖤 **Minimalist & Enigmatic Design**: Elegant black-and-white interface powered by the *Cinzel* typography.

---

## 🛠️ Technology Stack

### Backend
- **Java 21+**
- **Spring Boot 3.4+** (Web MVC & WebSockets)
- **Spring Data JPA & SQLite JDBC** (`org.xerial:sqlite-jdbc`)
- **Gson** (JSON Parsing)

### Frontend
- **HTML5 & CSS3** (CSS Custom Properties & Flexbox Layouts)
- **Vanilla JavaScript** (WebSockets API & Web Audio API for native sound effects)
- **QRCode.js** (Dynamic client-side QR Code rendering)
- **Google Fonts** (*Cinzel* for enigmatic titles & *Inter* for body text)

---

## 🚀 Getting Started

### Prerequisites
- **Java Development Kit (JDK 21 or higher)**
- **Git**

### Installation & Local Execution

1. **Clone the repository:**
   ```bash
   git clone https://github.com/your-username/riddles-backend.git
   cd riddles-backend
   ```

2. **Run the Spring Boot application:**
   - On Windows (PowerShell / Command Prompt):
     ```cmd
     .\gradlew.bat bootRun
     ```
   - On Linux / macOS:
     ```bash
     ./gradlew bootRun
     ```

3. **Open the TV interface:**
   Open your browser and navigate to:
   ```
   http://localhost:8080/tv.html
   ```

4. **Connect your phone:**
   Select **Single Player** or **Multiplayer** on the TV, scan the QR code with your smartphone (connected to the same Wi-Fi network), and start playing!

---

## 🌐 Free Cloud Deployment (Render.com)

You can deploy the entire application (Backend + WebSockets + SQLite + Frontend) for free on **Render.com**:

1. Push your repository to **GitHub**.
2. Log into [Render.com](https://render.com) and click **New +** > **Web Service**.
3. Connect your GitHub repository and configure the following settings:

| Property | Value |
| :--- | :--- |
| **Language** | `Java` |
| **Build Command** | `./gradlew build -x test` |
| **Start Command** | `java -jar build/libs/riddles-backend-0.0.1-SNAPSHOT.jar` |
| **Instance Type** | `Free` |

4. Click **Create Web Service**. Once deployed, Render will generate a public HTTPS URL (e.g., `https://riddles-game.onrender.com/tv.html`) that works globally over 4G/5G and any Wi-Fi connection!

---

## 📡 API & WebSocket Reference

### REST Endpoints

- **`GET /api/ranking`**
  - Returns the Top 10 leaderboard entries sorted by highest score.
- **`POST /api/ranking`**
  - Submits a new player score.
  - **Payload:** `{ "name": "Alice", "email": "alice@example.com", "score": 12, "mode": "single" }`
- **`GET /api/ip`**
  - Returns the server's local LAN IP address for QR Code auto-configuration.

### WebSocket Protocol

- **Endpoint:** `/ws`
- **TV Connection:** `/ws?role=tv&mode=single` or `/ws?role=tv&mode=multi`
- **Player Connection:** `/ws?role=player&room={ROOM_CODE}`

---

## 📜 License

This project is open-source and available under the [MIT License](LICENSE).
