# AzReconnect

A lightweight **Velocity plugin** that automatically reconnects players to a target server when it becomes available again.

Perfect for Minecraft networks where backend servers (like survival or minigames) restart frequently.

---

## ✨ Features

- 🔄 **Automatic Reconnect**  
  Players will automatically reconnect to a server after it restarts.

- ⚡ **Fast Detection**  
  Detects when a server goes online again and reconnects the player.

- 🌐 **Velocity Support**  
  Built specifically for **Velocity Proxy**.

- 🪶 **Lightweight**  
  Minimal performance impact.

- 🛠 **Developer Friendly**  
  Clean and simple implementation.

---

## 📦 Use Case

Example network setup:

Player → Velocity Proxy → Survival Server

Scenario:

1. Player joins **Survival**
2. Survival server **restarts**
3. Player is sent to **Hub**
4. When Survival is back online
5. Player is **automatically reconnected to Survival**

No need to run `/server survival` again.

---

## 📥 Installation

1. Download the latest release
2. Put the plugin into your Velocity plugins folder:

/plugins/

3. Restart your proxy.

---

## ⚙ Requirements

- Java 17+
- Velocity Proxy 3.x+

---

## 📚 Example Network

Example configuration:

Velocity (Proxy)
├── Lobby
├── Survival
└── Minigames

If **Survival restarts**, players will move like this:

Survival → Lobby → Survival (auto reconnect)

---

## 🔧 Building

Clone the repository:

git clone https://github.com/Azmide/Azreconect.git

Build with Maven:

mvn clean package

The compiled plugin will appear in:

/target/

---

## 📜 License

This project is licensed under the MIT License.

---

## 👤 Author

Developed by **Azmide**

GitHub:  
https://github.com/Azmide

---

## ⭐ Support

If you like this plugin, consider giving the repository a ⭐ on GitHub!