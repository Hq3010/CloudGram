<div align="center">

# ☁️ CloudGram

**Your files. Your cloud. Your privacy.**

A modern cloud storage & messaging Android app built on Telegram's open-source platform.

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![API 21+](https://img.shields.io/badge/API-21%2B-brightgreen)](https://developer.android.com/about/versions/lollipop)
[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)

---

</div>

## 📖 About

**CloudGram** is an Android application that transforms Telegram's powerful cloud infrastructure into a personal cloud storage solution. Built on top of [Telegram's open-source codebase](https://github.com/DrKLO/Telegram), CloudGram adds a dedicated **TeleDrive** file manager, an enhanced music player, privacy protection features, and a refreshed modern UI — all while retaining full Telegram messaging capabilities.

Whether you want to securely store documents, photos, music, or any file type — CloudGram gives you **unlimited cloud storage** powered by Telegram's infrastructure, wrapped in a clean and intuitive interface.

---

## ✨ Features

### ☁️ TeleDrive — Cloud File Manager
- **Upload any file** directly to your personal cloud with real-time progress tracking
- **Organize with folders** — create, rename, and manage folders to keep files structured
- **Smart file detection** — automatic categorization by type (documents, images, music, videos, etc.)
- **In-app file viewer** — open and preview files without leaving the app
- **Share & delete** — easily share files or remove them with modern confirmation dialogs
- **Upload status** — live progress bar with "Completed" indicator on successful uploads

### 🎵 Enhanced Music Player
- **Redesigned player UI** — clean, modern interface with album art, playback controls, and progress bar
- **Background playback** — toggle background audio playback on/off directly from the player
- **Seamless integration** — plays music files stored in your TeleDrive cloud
- **Full playback controls** — play, pause, skip, seek, and repeat

### 🔒 App Lock & Privacy
- **Multiple lock methods** — secure your app with PIN (6-digit), password, or pattern lock
- **Easy toggle** — enable or disable app lock from Settings at any time
- **Privacy-first** — lock screen appears on every app launch when enabled
- **Secure credential storage** — passwords and PINs are stored safely on-device

### 🎨 Modern UI & Branding
- **Custom CloudGram branding** — unique app icon with cloud + upload arrow design
- **Animated splash screen** — beautiful launch animation with CloudGram identity
- **Material Design dialogs** — redesigned file action dialogs (open, share, delete) with clean rounded UI
- **English-only interface** — streamlined, consistent language throughout the app
- **Blue gradient theme** — cohesive color scheme across the entire app

### 💬 Full Telegram Messaging
- All core Telegram features: chats, groups, channels, voice/video calls
- End-to-end encrypted secret chats
- Stickers, GIFs, media sharing
- Multi-device support via Telegram's cloud sync

---

## 📱 Screenshots

> *Coming soon — screenshots of TeleDrive file manager, music player, app lock, and CloudGram branding.*

---

## 🏗️ Architecture

```
CloudGram
├── TMessagesProj/                  # Main application module
│   └── src/main/
│       ├── java/org/telegram/
│       │   ├── messenger/
│       │   │   └── drive/          # TeleDrive cloud storage engine
│       │   │       ├── DriveRepository.java       # Upload/download/sync logic
│       │   │       ├── DriveController.java        # UI controller layer
│       │   │       ├── DriveAppLockManager.java    # App lock credential manager
│       │   │       └── ...
│       │   └── ui/
│       │       ├── drive/          # TeleDrive UI screens
│       │       │   ├── DriveActivity.java          # Main file browser
│       │       │   ├── DriveMusicPlayerActivity.java  # Enhanced music player
│       │       │   └── DriveAppLockActivity.java   # Lock screen UI
│       │       └── ...
│       └── res/
│           ├── drawable/
│           │   ├── tg_splash_320.xml               # Animated splash screen
│           │   ├── ic_launcher_foreground_vec.xml   # Adaptive icon foreground
│           │   └── icon_background_sa.xml          # Blue gradient background
│           └── mipmap-*/           # App icons for all screen densities
├── TMessagesProj_App/              # App packaging module
├── gradle/                         # Gradle wrapper
└── Tools/                          # Build utilities
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version |
|------|---------|
| **Android Studio** | Hedgehog (2023.1) or later |
| **Android SDK** | API 35 (compileSdk) |
| **Android NDK** | r20 or compatible |
| **Min SDK** | API 21 (Android 5.0 Lollipop) |
| **Java** | JDK 11+ |

### Build Instructions

1. **Clone the repository**
   ```bash
   git clone https://github.com/YourUsername/CloudGram.git
   cd CloudGram
   ```

2. **Configure signing**
   - Copy your `release.keystore` into `TMessagesProj/config`
   - Update `gradle.properties`:
     ```properties
     RELEASE_KEY_PASSWORD=your_password
     RELEASE_KEY_ALIAS=your_alias
     RELEASE_STORE_PASSWORD=your_store_password
     ```

3. **Set up Firebase**
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Create Android apps with your application ID
   - Download `google-services.json` → place in `TMessagesProj/`

4. **Configure API credentials**
   - [Obtain your Telegram API ID](https://core.telegram.org/api/obtaining_api_id)
   - Fill in values in `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`

5. **Build the APK**
   ```bash
   # Debug build (universal APK)
   ./gradlew :TMessagesProj_App:assembleAfatDebug

   # Release build
   ./gradlew :TMessagesProj_App:assembleAfatRelease
   ```

6. **Install via ADB**
   ```bash
   adb install -r TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
   ```

---

## 🛠️ Tech Stack

| Component | Technology |
|-----------|------------|
| **Language** | Java |
| **UI Framework** | Android Views + Custom Drawables |
| **Cloud Backend** | Telegram MTProto API |
| **File Storage** | Telegram Cloud (unlimited) |
| **Animations** | AnimatedVectorDrawable, ObjectAnimator |
| **Database** | SQLite (local metadata) |
| **Build System** | Gradle |
| **Encryption** | MTProto 2.0 (Telegram protocol) |

---

## 🗺️ Roadmap

- [ ] Multi-language support
- [ ] File search within TeleDrive
- [ ] Bulk file operations (multi-select, bulk delete)
- [ ] File sharing links (public/private)
- [ ] Automatic photo/video backup
- [ ] Biometric authentication (fingerprint / face unlock)
- [ ] Dark theme for TeleDrive screens
- [ ] File encryption at rest (local)
- [ ] Desktop companion app

---

## 🤝 Contributing

Contributions are welcome! If you'd like to improve CloudGram:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

Please ensure your code follows the existing project structure and conventions.

---

## 📄 License

This project is licensed under the **GNU General Public License v2.0** — see the [LICENSE](LICENSE) file for details.

CloudGram is built upon [Telegram for Android](https://github.com/DrKLO/Telegram), which is released under GPL v2.0.

---

## 🙏 Acknowledgements

- [Telegram](https://telegram.org) — for the incredible open-source messaging platform
- [Telegram for Android](https://github.com/DrKLO/Telegram) — the foundation this project is built on
- All contributors who help make CloudGram better

---

<div align="center">

**Made with ❤️ by the CloudGram Team**

*CloudGram is an independent project and is not affiliated with Telegram Messenger.*

</div>