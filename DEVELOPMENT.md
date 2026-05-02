# 開発環境セットアップ

## プロジェクト概要

EPGStation の録画番組を Android TV / Fire TV で視聴するクライアントアプリ。

- **言語:** Kotlin 2.0.21
- **最小 SDK:** Android 5.1 (API 22)
- **ターゲット SDK:** Android 14 (API 34)
- **コンパイル SDK:** Android 14 (API 34)
- **AGP:** 8.7.3
- **Gradle:** 8.9

> 注: 元のコードは Kotlin 1.5.31 / AGP 7.0.3 / Gradle 7.0.2 / compileSdk・targetSdk 30 だったが、
> Android Studio Panda (JDK 21 バンドル) との互換性および Compose for TV 移行準備のため更新済み。

---

## 必要なツール

| ツール | 要件 | 推奨 |
|--------|-----------|------|
| Git | 2.x 以上 | 2.51.0 |
| Android Studio | Panda 4  | Panda 4 (2025.3.4) |
| JDK | 21 (Android Studio 同梱) | OpenJDK 21.0.10 |
| Android SDK | API 34 (Android 14) |  |
| Android Build Tools | AGP 8.7.3 デフォルト (自動) |  |

**インストール先**
- Android Studio: `C:\Program Files\Android\Android Studio`
- JDK: `C:\Program Files\Android\Android Studio\jbr`
- Android SDK: `%LOCALAPPDATA%\Android\Sdk`

---

## セットアップ手順

### 1. リポジトリのクローン

```powershell
git clone https://github.com/daig0rian/epcltvapp "c:/Users/daigo/epcltvapp"
```

---

### 2. Android Studio のインストール

Android Studio Panda 4 (2025.3.4) を Standard セットアップでインストール。
JDK 21 および Android SDK (API 36.1) が自動インストールされる。

---

### 3. Android SDK に API 34 を追加インストール

Standard セットアップでは最新 API (36.1) のみインストールされる。
`compileSdkVersion 34` でビルドするには **API 34 (Android 14)** が必要。

1. Android Studio を起動
2. `Tools > SDK Manager` を開く
3. **SDK Platforms タブ** で `Android 14.0 (API Level 34)` にチェックを入れる
4. `Apply` → `OK` でインストール

> Build Tools は `buildToolsVersion` を指定していないため AGP 8.7.3 が自動で適切なバージョンを使用する。


---

### 4. プロジェクトを Android Studio で開く

1. Android Studio を起動
2. `Open` → `c:/Users/daigo/epcltvapp` を選択
3. Gradle sync が自動で開始されるので完了まで待つ


---

### 5. ビルド確認

```
Build > Make Project (Ctrl+F9)
```


---

### 6. エミュレーターのセットアップ

1. `Tools > Device Manager` → `+` → Create Virtual Device
2. Category: **TV** → `Android TV (1080p)` → Next
3. `x86 Images` タブ → **API 34** のイメージをダウンロード → Next → Finish
4. ▶ で起動後、Android Studio の実行ターゲットに選択して Run

> API 34 が targetSdkVersion と一致するため推奨。古い端末の動作確認には API 28〜30 のイメージも併用できる。


---

## 依存関係バージョン管理

### JDK ↔ Gradle ↔ AGP 互換性

| JDK | 最低 Gradle | 最低 AGP |
|-----|-------------|---------|
| 11  | 7.0+        | 7.0+    |
| 17  | 7.3+        | 7.2+    |
| 21  | 8.5+        | 8.3+    |

Android Studio Panda は JDK 21 をバンドルしているため、
Gradle 8.5 以上・AGP 8.3 以上が必要。

### 現在のバージョン

`gradle/wrapper/gradle-wrapper.properties`:
```
distributionUrl=https://services.gradle.org/distributions/gradle-8.9-bin.zip
```

`build.gradle` (ルート):
```groovy
classpath 'com.android.tools.build:gradle:8.7.3'
kotlin_version = "2.0.21"
```

`app/build.gradle`:
```groovy
compileSdkVersion 34
minSdkVersion 22
targetSdkVersion 34
```

---

## 既知の技術的負債

| 項目 | 内容 | 対応方針 |
|------|------|---------|
| `SettingsFragment.kt` の `PreferenceFragment` | deprecated 警告が残存 | `leanback-preference` stable 1.1.0 が存在しないため保留。Compose for TV 移行時に解消 |

---

## IDE の使い分け

| 用途 | ツール |
|------|--------|
| コード編集・Claude との会話 | VS Code + Claude Code 拡張 |
| Gradle sync・ビルド・デバッグ・エミュレーター | Android Studio |

同じフォルダを両方で同時に開いて使い分けることができる。
VS Code で編集したファイルは Android Studio に自動反映される。

---

## トラブルシューティング

### JAVA_HOME が設定されていない場合

Android Studio のターミナルから実行する場合は以下を設定:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
```

### Gradle sync が失敗する場合

- `File > Invalidate Caches / Restart` を試す
- `File > Project Structure > SDK Location` で JDK が `C:\Program Files\Android\Android Studio\jbr` を向いているか確認

---

## 実機デバッグ

### Android TV / Fire TV 実機接続

1. デバイスの開発者オプションで ADB デバッグを有効化
2. 同一 LAN に接続
3. ネットワーク経由で接続:

```powershell
adb connect <device-ip>:5555
```

### Fire TV Stick 世代別 Android API レベル

| デバイス | 発売年 | API |
|---------|--------|-----|
| Fire TV Stick 4K (初代) | 2018 | 25 |
| Fire TV Stick 3rd gen / Lite | 2020 | 28 |
| Fire TV Stick 4K Max (初代) | 2021 | 28〜30 |
| Fire TV Stick 4K (2nd gen) / 4K Max (2nd gen) | 2023 | 30 |
| Fire TV Stick HD | 2024 | 28 |
| Fire TV Stick (2025年以降の新モデル) | 2025〜 | Vega OS (Android 非対応) |

> minSdkVersion 22 は全 Android 系 Fire TV 世代をカバーする。

---

*最終更新: 2026-05-02*
