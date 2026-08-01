# WIP: minSdk 23 引き上げ + media3 1.9.2 アップグレード

## 目的

Phase 2 (TSシークバーへのサムネイル表示、[feature/ts-seek-thumbnails](https://github.com/daig0rian/epcltvapp/tree/feature/ts-seek-thumbnails)) で、生ARIB TSからのフレーム抽出に `MediaMetadataRetriever` が使えないことが判明した。代替として media3 1.9.0 以降に追加された `media3-inspector` の `FrameExtractor` API (ExoPlayerの独自TsExtractorをそのまま使ってフレームを取得できる) が有力候補だが、media3 1.9.0 は minSdk 23 を要求し、epcltvappは Fire TV 全世代サポートのため minSdk 22 を維持する方針だった。

調査の結果、minSdk=22 が支えていた API22 世代 (Fire TV 1st/2nd Gen, Fire TV Stick 1st/2nd Gen の4機種) は、Amazonの公式サポート終了通知 (2024-11-30発表、Fire TV 1st/2nd Genが対象。Fire TV Stick 1st/2nd Genも同時期にガイド保証アップデート期限を超過) によりすでにソフトウェア更新が止まっていることが確認できた。この4機種を切る前提で minSdk を 23 に引き上げ、media3を1.9.2へアップグレードする。

このブランチのスコープは **依存関係とminSdkの引き上げのみ**。FrameExtractorを使った実際のサムネイル実装は `feature/ts-seek-thumbnails` ブランチ側で別途行う(このブランチがmasterにマージされた後、そちらをrebaseして着手)。

## 完了済み

- [x] `app/build.gradle`: `minSdkVersion 22` → `23`
- [x] `app/build.gradle`: `media3_version` `1.3.1` → `1.9.2`
- [x] `CLAUDE.md`: 技術スタック・依存関係バージョン表・将来の方針のminSdk記述を更新
- [x] **ビルド1回目失敗・修正済み**: `assembleDebug`が`checkDebugAarMetadata`で失敗。media3 1.9.2の全モジュールが「compileSdk 35以上必須」を要求(実際にはAARメタデータのハード要件であり、リリースノート上の「R8のcompileSdk=35相当バージョン」という記述より強い制約だった)。`compileSdkVersion`を`34`→`35`に変更して対応。`targetSdkVersion`は挙動変更(edge-to-edge強制等)を避けるため`34`のまま据え置き(compileSdk >= targetSdkの制約は満たしている)。

## 残タスク

- [ ] **ユーザー側でAndroid SDK Platform 35のインストールが必要**: ローカルSDKには`android-34`と`android-36.1`のみ存在し`android-35`が未インストールを確認済み。Android StudioのSDK Managerからインストールしてから再ビルドが必要。
- [ ] **ユーザーによるビルド確認待ち**: Android Studioでのgradle sync・assembleDebug・実機/エミュレータでの動作確認 (media3を1.3.1→1.9.2へ6マイナーバージョン分一気に上げるため、既存のTS再生・シーク機能(`TsReadexDataSource`, `TsSeekPlayerAdapter`, `TsSeekDataProvider`)、および非TSコンテンツ再生(`LeanbackPlayerAdapter`経由)の両方で回帰がないか要確認)
- [ ] 動作確認が取れ次第、コミットしてPR作成 (`feature/ts-seek-thumbnails`とは無関係な独立した変更のため、先にこちらをマージする)

## 調査済みの事実 (このブランチの判断根拠)

### media3 1.3.1 → 1.9.2 の変更点調査結果

- **minSdk**: 1.3.0時点で19、1.5.0で21、**1.9.0で23**に引き上げ。1.9.0がepcltvappのminSdk=22方針と衝突する唯一の境界。
- **`media3-ui-leanback`モジュール** (`LeanbackPlayerAdapter`、非TS再生で使用中): 1.3.1〜1.9.2のリリースノート全件を確認したが変更なし。実質凍結されており今回のアップグレードでの破壊的変更リスクは低い。
- **カスタム`DataSource`実装** (`TsReadexDataSource`): `DataSource`インターフェース自体への破壊的変更なし。1.4.0で削除された`CronetDataSourceFactory`等は未使用のため無関係。
- **`Player`インターフェース**: `hasNext()`/`hasPrevious()`等の非推奨メソッド削除があるが、epcltvappは単一プログラム再生のみで未使用 (grep確認済み)。
- **Kotlin要件**: media3 1.6.0でKotlin 2.0.20が要求されるが、epcltvappは既にKotlin 2.0.21のため無関係。
- **「破壊的変更」と明記されたのは1.9.0内の2件のみ**: HLS拡張の`onAssetListLoadCompleted`コールバック引数追加、DRMの`MediaDrmCallback`戻り値型変更。どちらも未使用機能。
- 1.7.0は誤ってstableタグ付けされた欠陥リリース(公式が「使うな」と明記)。1.7.1は1.6.1と同一内容。

### Fire TVデバイス世代とAPIレベル (Amazon公式開発者ドキュメントより)

Amazon公式の「Identify Fire TV Devices」ページの実データ確認済み。API22 (Fire OS 5) 世代は以下の4機種のみ:

| 機種 | Build Model | 発売 | Amazon保証アップデート期限 |
|---|---|---|---|
| Fire TV 1st Gen (Box) | AFTB | 2014-04 | 〜2019年 |
| Fire TV Stick 1st Gen | AFTM | 2014-11 | 〜2020年 |
| Fire TV 2nd Gen (Box) | AFTS | 2015-09 | 〜2021年 (公式終了通知2024-11-30) |
| Fire TV Stick 2nd Gen / Basic Edition | AFTT | 2016-2019 | 〜2024年9月 |

Fire TV 3rd Gen (2017, pendant) 以降はFire OS 6以上 (API25+) であり、minSdk=23への引き上げでは一切影響を受けない。

なお2025年以降の最新機種 (Fire TV Stick 4K Select, Fire TV Stick HD 2026) はAmazonが独自のVega OS (非Android) へ移行済みで、そもそもAndroidアプリの対象外。

## 重要な決定事項

- ユーザー承認済み: 2026-08-01、API22世代4機種を切り捨てる前提でminSdk23への引き上げとmedia3アップグレードに合意。
- ビルドはAndroid Studioでユーザーが手動実行する (Claude Codeはgradleを叩かない)。
