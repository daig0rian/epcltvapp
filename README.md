# EPGStation の録画を見る
"EPGStation の録画を見る" は Android TV / Fire TV 向けに設計された EPGStation クライアントアプリです。

リモコンの↑↓←→キー操作が基本となる Android TV から EPGStation の録画を見るために開発されました。

## 特徴
- Android TV 向け標準 UI の Leanback テーマを使用
- Android TV のリモコンだけで操作が完結
- Android TV のリモコン内蔵マイクから録画済番組を声で検索
- MX Player や VLC といった外部動画プレーヤーに対応
- 内蔵プレーヤーで ARIB STD-B24 字幕に対応（v1.28〜）

## 必要な環境
- EPGStation Version 1.x.x または 2.x.x（Version 1.x.x については動作確認が限定的です）
- Android 5.1 以上の Android TV / Fire TV

## テスト環境
- SONY ブラビア KJ-43X8000H（ Android TV / Android 10 / 4K )
- EPGStation Version 2.10.0

## インストール方法

### Downloader by AFTVnews を使う（推奨）

1. Fire TV / Android TV の App Store から **[Downloader by AFTVnews](https://www.aftvnews.com/downloader/)** をインストールする。
2. [Releases](https://github.com/daig0rian/epcltvapp/releases) の最新リリースノートに記載された **ショートコード（数字6桁）** を確認する。
3. Downloader を起動し、ショートコードを入力すると APK が自動的にダウンロードされる。
4. ダウンロード完了後、画面の案内に従ってインストールする。

### APK を直接転送する（代替手段）

[Releases](https://github.com/daig0rian/epcltvapp/releases) の最新 `app-release.apk` を「Send Files To TV」などのアプリで TV に送信しインストール。

---

## How to Install

### Using Downloader by AFTVnews (Recommended)

1. Install **[Downloader by AFTVnews](https://www.aftvnews.com/downloader/)** from the App Store on your Fire TV / Android TV.
2. Open the [latest release notes](https://github.com/daig0rian/epcltvapp/releases) and find the **shortcode (6-digit number)** listed there.
3. Launch Downloader, enter the shortcode, and the APK will be downloaded automatically.
4. Follow the on-screen instructions to install after the download completes.

### Sideload via file transfer (alternative)

Download the latest `app-release.apk` from [Releases](https://github.com/daig0rian/epcltvapp/releases) and transfer it to your TV using an app such as "Send Files To TV".


## ストアへの公開状況
- 2021/06/10 Google Play Store での公開審査完了（3日間・審査1回目でパス）
- 2021/06/11 Android TV 端末への配信オプトイン審査完了（操作から1日未満で完了）
- 2021/07/01 Amazon Appstore 公開審査完了（26日間・審査7回目でパス）
- 2022/03/04 第三者（日本国内で放送されている内容に利害関係がある団体）からの Amazon アプリストアのコンテンツポリシー違反の指摘により Amazon アプリストアから削除
- 2022/03/04 上記指摘を受け Google Play Store での公開も停止
