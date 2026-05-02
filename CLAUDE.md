# epcltvapp プロジェクト概要

EPGStation の録画番組を Android TV / Fire TV で視聴するクライアントアプリ。
Kotlin 製 Android TV アプリ (Leanback UI フレームワーク使用)。

- GitHub: https://github.com/daig0rian/epcltvapp
- ライセンス: MIT

## 現在の状態

休眠していたプロジェクト (最終リリース v1.27 / 2022年1月) の開発再開済み (2026-05-02)。
依存関係・SDK バージョンを現代的な構成に更新し、UI を Compose for TV に移行中。
最新リリース: **v1.28 (2026-05-03)**

## 技術スタック

- **言語:** Kotlin 2.0.21
- **UI:** Leanback (Android TV) + Compose for TV (Settings・Playback 移行済み) ※段階的に移行中
- **ネットワーク:** Retrofit2 + Gson
- **画像:** Glide
- **動画再生:** libVLC (内蔵) + 外部プレーヤー対応 (MX Player / VLC)
- **最小 SDK:** API 22 (Android 5.1)
- **コンパイル SDK:** API 34 (Android 14)
- **ターゲット SDK:** API 34 (Android 14)

## 開発環境

- **OS:** Windows 11
- **Android Studio:** Panda 4 (2025.3.4)
- **VS Code:** インストール済み (Claude Code 拡張で主に使用)
- **JDK:** OpenJDK 21.0.10 (Android Studio バンドル)
  - パス: `C:\Program Files\Android\Android Studio\jbr`
- **Android SDK:** `C:\Users\daigo\AppData\Local\Android\Sdk`
  - API 30 + Build-Tools 30.0.3 インストール済み
  - API 36.1 + Build-Tools 36.1.0 / 37.0.0 インストール済み (Standard セットアップ)

## 依存関係バージョン

| 項目 | 元の値 | 現在値 | 更新理由 |
|------|--------|--------|---------|
| Gradle | 7.0.2 | **8.9** | JDK 21 非対応のため |
| AGP | 7.0.3 | **8.7.3** | JDK 21 非対応のため |
| Kotlin | 1.5.31 | **2.0.21** | JDK 21 対応 |
| compileSdk | 30 | **34** | Compose for TV 移行準備 |
| targetSdk | 30 | **34** | compileSdk に合わせて更新 |
| minSdk | 22 | **22 (変更なし)** | Fire TV 全世代サポート維持 |
| leanback | 1.0.0 | **1.0.0 (変更なし)** | stable 1.1.0 が存在しないため |
| Compose BOM | (新規) | **2024.12.01** | Settings 画面 Compose 移行 |
| tv-material | (新規) | **1.0.0** | Settings 画面 Compose 移行 |
| activity-compose | (新規) | **1.9.3** | Settings 画面 Compose 移行 |
| libVLC | 3.4.7 | **4.0.0-eap24** | ARIB STD-B24 字幕対応 |

## 既知の技術的負債

現時点で既知の技術的負債はなし。

## Compose for TV 移行状況

| 画面 | 状況 | 備考 |
|------|------|------|
| Settings | ✅ 完了 (2026-05-03) | `PreferenceFragment` 問題を同時解消 |
| Search | 未着手 | Leanback `SearchSupportFragment` 使用中 |
| Browse (メイン) | 未着手 | Leanback `BrowseSupportFragment` 使用中 |
| Details | 未着手 | Leanback `DetailsSupportFragment` 使用中 |
| Playback | ✅ 完了 (2026-05-03) | libVLC 4.0 + ARIB 字幕対応を同時実施 |

## 将来の方針

- **Leanback は deprecated**（Google 公式、2026年3月）。新機能追加なし・メンテナンスのみ。
- **Compose for TV (`androidx.tv:tv-material`)** が Google 推奨の新方向。
- 移行戦略：Settings 完了後、Search → Browse → Details/Playback の順で段階的に移行。
- `TvLazyColumn` は `tv-foundation:1.0.0` で削除済み。標準 `LazyColumn` を使用すること。
- minSdkVersion は 22 を維持。Compose の最低要件 (API 21) を満たしており変更不要。

## セットアップ進捗

- [x] リポジトリのクローン (`c:/Users/daigo/epcltvapp`)
- [x] Android Studio Panda 4 インストール
- [x] Android SDK API 30 + Build-Tools 30.0.3 インストール
- [x] Gradle sync
- [x] ビルド確認 (assembleDebug BUILD SUCCESSFUL)
- [x] エミュレーター (Android TV API 30) での動作確認
- [x] deprecated API 対応 (nonTransitiveRClass / Display.getMetrics / getSerializableExtra)
- [x] compileSdk / targetSdk 34 への引き上げ・API 31〜34 行動変更対応
- [x] Settings 画面を Compose for TV に移行 (PreferenceFragment 問題解消)
- [x] Playback 画面を Compose for TV に移行 (PlaybackViewModel + PlaybackScreen)
- [x] libVLC 4.0.0-eap24 にアップデート (ARIB STD-B24 字幕対応)

## 詳細ドキュメント

→ [DEVELOPMENT.md](DEVELOPMENT.md) を参照
