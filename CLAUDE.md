# epcltvapp プロジェクト概要

EPGStation の録画番組を Android TV / Fire TV で視聴するクライアントアプリ。
Kotlin 製 Android TV アプリ (Leanback UI フレームワーク使用)。

- GitHub: https://github.com/daig0rian/epcltvapp
- ライセンス: MIT

## 現在の状態

休眠していたプロジェクト (最終リリース v1.27 / 2022年1月) の開発再開済み (2026-05-02)。
依存関係・SDK バージョンを現代的な構成に更新し、ビルド・エミュレーター動作確認済み。

## 技術スタック

- **言語:** Kotlin 2.0.21
- **UI:** Leanback (Android TV) ※将来的に Compose for TV への移行を計画
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

## 既知の技術的負債

| 項目 | 状況 | 理由 |
|------|------|------|
| `SettingsFragment.kt` の `PreferenceFragment` | 警告あり・保留中 | `leanback-preference` stable 1.1.0 が存在しない。Leanback 自体が deprecated のため今後は Compose for TV への移行で解消予定 |

## 将来の方針

- **Leanback は deprecated**（Google 公式、2026年3月）。新機能追加なし・メンテナンスのみ。
- **Compose for TV (`androidx.tv:tv-material`)** が Google 推奨の新方向。
- 移行戦略：既存 Leanback Fragment に `ComposeView` を埋め込む段階的移行を計画。
  - 最初のターゲット：Settings 画面（`PreferenceFragment` 問題も同時解消）
- minSdkVersion は Compose 移行まで 22 を維持。移行時も Compose の最低要件 (API 21) を満たしており変更不要。

## セットアップ進捗

- [x] リポジトリのクローン (`c:/Users/daigo/epcltvapp`)
- [x] Android Studio Panda 4 インストール
- [x] Android SDK API 30 + Build-Tools 30.0.3 インストール
- [x] Gradle sync
- [x] ビルド確認 (assembleDebug BUILD SUCCESSFUL)
- [x] エミュレーター (Android TV API 30) での動作確認
- [x] deprecated API 対応 (nonTransitiveRClass / Display.getMetrics / getSerializableExtra)
- [x] compileSdk / targetSdk 34 への引き上げ・API 31〜34 行動変更対応

## 開発フロー (GitHub Flow)

このリポジトリは **GitHub Flow** で開発する。Claude Code は以下のルールを必ず守ること。

### ブランチ運用

- `master` が常にデプロイ可能な状態を保つ唯一のメインブランチ。
- 作業を始める前に必ず `master` から **フィーチャーブランチ** を切る。
  - 命名: `feature/<概要>` / `fix/<概要>` / `chore/<概要>` など (kebab-case・英語)
  - 例: `feature/compose-settings-screen`, `fix/subtitle-crash`
- ブランチは作業単位ごとに切る。複数の無関係な変更を 1 ブランチにまとめない。

### コミット・PR

- PR タイトルは変更内容を簡潔に表す日本語または英語。
- マージ後は速やかにフィーチャーブランチを削除する。
- フィーチャーブランチは **squash merge** するため、コミット数が多くても問題ない。実装チェックポイントごとに細かくコミットすること。
  - 特に、試行錯誤が予想されるリスクのある変更（レイアウト調整・Leanback の挙動修正など）を始める**前**に、その時点の動作確認済み状態を必ずコミットする。
  - こうすることで「ちゃんと動いていた時点」へ `git checkout <hash> -- .` で安全に戻れる。revert は記憶や要約に頼らずコミットハッシュを使う。

### Claude Code が守るべき行動

1. ユーザーから実装タスクを受けたら、まず適切な名前のブランチを作成してから作業を開始する。
2. 実装がひと段落したら、PR を作成する前に**ユーザーに動作確認を促す**。
   - 実装した内容を簡潔に説明し、ビルドと実行、動作確認を促す。
   - ユーザーが追加修正を求めた場合は、同じブランチで対応を続ける。
3. ユーザから動作確認完了の確認が取れたら、`gh pr create` で **Pull Request** を作成する。
   - PR URL をユーザーに報告し、テスト項目の消化を促す。
4. ユーザーから「マージした」の報告を受けたら、以下の後片付けを行う。
   - `git checkout master && git pull origin master` でローカルを最新化する。
   - `git branch -d <ブランチ名>` でローカルブランチを削除する。
   - `git push origin --delete <ブランチ名>` でリモートブランチを削除する。
5. `master` へ直接コミット・プッシュしない（緊急 hotfix・ドキュメント修正等の場合はユーザーに確認を取る）。ただし**リリース準備（バージョン番号更新・リリースノート作成）は直接 master にコミット**して構わない。コードの変更ではなくメタデータの更新であり、ブランチ/PR のオーバーヘッドに見合わないため。

### リリースフロー

タグ (`v*`) を push すると GitHub Actions が自動的にリリース APK をビルドし GitHub Release を作成する。
リリースノートは `release-notes/<タグ名>.md`（例: `release-notes/v1.29.md`）に置くと自動的に Release の本文に反映される。

**リリース手順:**

1. リリース対象の変更がすべて `master` にマージされていることを確認する。
2. **ブランチを切らず master で直接作業する**（リリース準備はメタデータ更新のため）。
3. `app/build.gradle` の `versionCode` / `versionName` を更新する。
4. `release-notes/v<バージョン>.md` にエンドユーザー向けリリースノートを作成する。
   - Claude Code はこのファイルを書く役割を担う。
   - 記載内容: 追加機能・変更点・削除された機能・ユーザーに対応が必要な事項。
5. 上記2ファイルをまとめて master にコミット・プッシュする。
6. タグを打つ: `git tag v<バージョン> && git push origin v<バージョン>`
5. GitHub Actions が自動的にビルド・署名・リリース作成・APK アップロードを行う。

## 詳細ドキュメント

→ [DEVELOPMENT.md](DEVELOPMENT.md) を参照
