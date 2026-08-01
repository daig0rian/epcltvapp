# WIP: TSシークバーへのサムネイル表示 (Phase 2)

## 目的

Phase 1（[#43](https://github.com/daig0rian/epcltvapp/pull/43)、マージ済み）でTS再生のシーク機能を実装した。そのシーク点の一部にサムネイルを付与する。

## ⚠️ このブランチは現在スパイク(実験)状態

`FrameExtractor.setMediaSourceFactory()`(media3 1.10.0+)を使うためにはcompileSdk 36が必要で、
それにはAGP 9.0.1 + Gradle 9.1.0へのメジャーバージョンアップ(旧DSL/kotlin-androidプラグイン
非互換のBreaking Change付き)が必要と判明した。サムネイル機能のためのスコープとしては
過大という認識のもと、**「このアプローチのノックアウト点を見つける」ことを目的とした
スパイクとして継続中**。マージされない可能性がある前提で進めている。

- `android.newDsl=false` / `android.builtInKotlin=false`(`gradle.properties`)でAGP 9.0の
  破壊的変更を一時的にオプトアウトしつつ、compileSdk 36の解禁だけを得る方針で試行中。
- ユーザーはローカルSDKに「Android 16.0 (Baklava) API Level 36.0」(plain、36.1とは別)の
  追加インストールが必要な可能性がある(現状36.1のみ導入済み)。

## 確定した設計(2周目・実機検証を経て確定)

`androidx.media3.inspector.frame.FrameExtractor`（ExoPlayer自身のTsExtractorでフレームをデコードするAPI、media3 1.10.0でmedia3-inspector-frameモジュールに分離）を使う。media3を1.10.1にアップグレード済み([#44](https://github.com/daig0rian/epcltvapp/pull/44)は1.9.2への上げだったが、Phase 2実装中にこの後さらに1.10.1へ再アップグレードした)。

### 1周目の設計は実機検証で否定された

当初は「FrameExtractorはtsreadexを経由せずExoPlayer内蔵の`TsExtractor`が持つPCRベースの二分探索シーク機構(`TsBinarySearchSeeker`)がそのまま使えるはずなので、独自のバイト位置計算は不要」という設計で実装したが、実機検証(ADB logcat)で**全シーク点が同一の先頭付近フレームしか返さない**不具合が発生した。

原因はmedia3のソース(`FrameExtractorInternal.PlayerListener.onPlaybackStateChanged`)のコメントで確認: 「If the seek resolves to the current position, the renderer position will not be reset and extractedFrameNeedsRendering remains false. No frames are rendered. Repeat the previously returned frame.」——ExoPlayer自身の内部`seekTo()`が、このアプリのARIB TSに対しては要求位置に関わらず常に「現在位置と同じ」と判定し、実質何もしていなかった。Content-Lengthは正しく取得できていることをcurlで直接確認済みなので、それが原因ではない。

### 2周目の設計: Phase 1の実績あるシーク機構を流用する

`FrameExtractor`の内部`seekTo()`には一切頼らず、Phase 1で確立済みの「シーク点ごとに、その概算バイト位置(`TsSeekDataProvider.estimateByteOffset`)から開始する`MediaSource`を都度新規に構築する」方式(`TsReadexDataSource.startByteOffset`)をサムネイル生成にも流用する。

- media3 1.10.0以降で`FrameExtractor.Builder.setMediaSourceFactory(MediaSource.Factory)`が追加された(1.9.xには無い)。これによりシーク点ごとに`TsReadexDataSource.Factory(dataSourceFactory).apply { startByteOffset = ...; nativeProcessingEnabled = false }`を包んだ`ProgressiveMediaSource.Factory`を注入できる。
- 各シーク点で**新しい`MediaItem`(mediaIdを点ごとに変える)+新しい`FrameExtractor`インスタンス**を作る。これによりFrameExtractor内部の「同一MediaItemなら`seekTo()`で済ませる」再利用判定(`needsPrepare`)を確実に不成立にし、都度フルの再準備(prepare)をさせる。
- `getFrame(ms)`ではなく`getThumbnail()`を使う。常に「そのオフセット済みストリームの先頭付近のいいフレーム」を返すヒューリスティックで、機能しない`seekTo()`経路を通らない(内部で一度`positionMs=0`相当のprepare結果を取得し、それがヒューリスティックの目標と一致すればそのまま返す。一致せず追加の`seekTo()`が発生しても、既に正しい位置から始まっているストリームなので、seekTo()がバグって現在位置を繰り返すだけの場合でも実害がない)。
- `nativeProcessingEnabled = false`(tsreadexネイティブフィルタ不使用)。サムネイルにARIB字幕・音声処理は不要なため、オーバーヘッドを避ける。

旧調査コード(`investigateTsreadexNormalizedThumbnail`等、MediaMetadataRetriever経由の検証一式)は削除済み。1周目のFrameExtractor実装(seekTo依存)も置き換え済み。

### 認証について

このアプリの`buildOkHttpClient`はURLの`user:pass@host`を検出した場合のみBasic認証ヘッダーを付与する。`TsThumbnailGenerator`は自前で`OkHttpDataSource.Factory(httpClient)`を組み立てるため(`PlaybackVideoFragment`から渡された`httpClient`をそのまま使う)、1周目の設計(FrameExtractor標準パイプライン)と異なりこの認証機構の恩恵をそのまま受けられる。

## 実装済み

- [x] `app/build.gradle`: media3を1.10.1に、依存を`media3-inspector-frame`に変更
- [x] `TsThumbnailGenerator.kt`: シーク点ごとに`TsReadexDataSource`(startByteOffset指定・ネイティブ処理オフ)を包む`MediaSource.Factory`を都度構築し、`FrameExtractor.setMediaSourceFactory()`で注入。`getThumbnail()`で取得。二分木BFS順で1-in-10点を背景生成、`Map<Int, Bitmap>`キャッシュ
- [x] `TsSeekDataProvider.kt`: `httpClient`を追加受け取り、`estimateByteOffset`メソッド参照を`TsThumbnailGenerator`に渡す
- [x] `PlaybackVideoFragment.kt`: `TsSeekDataProvider`構築時に`client`を渡すよう変更

## 残タスク

- [ ] **ビルド確認待ち(スパイク中)**: AGP 9.0.1/Gradle 9.1.0/compileSdk 36で`assembleDebug`が通るか。通らなければそこがこのアプローチのノックアウト点
- [ ] ビルドが通ったら、2周目の設計でサムネイルが実際に(異なる内容で)表示されるか実機確認
- [ ] スパイクの結果次第で: (a)このままPR化して正式採用、(b)media3 1.9.2+AGP 8.7.3に戻してImageReader自前実装に切り替え、のいずれかを選ぶ

## 重要な決定事項

- ビルドはAndroid Studioでユーザーが手動実行する(Claude Codeはgradleを叩かない)。
- コルーチン未導入のプロジェクトのため、既存パターン(Handler + バックグラウンドThread、Guavaの`ListenableFuture`+`Futures.addCallback`)を踏襲する。
- サムネイルは全シーク点ではなく1-in-10点のみ生成する。対象外の点は`getThumbnail`のコールバックを呼ばないことで「サムネイル無し」を表現する(Leanback側もコールバック未呼び出しを許容する設計、leanbackソースで契約確認済み)。
- 実機での不具合調査はADB logcat経由で直接確認する(推測で進めない)。EPGStationサーバーへの直接curlでもContent-Length等のサーバー側挙動を確認できる。
