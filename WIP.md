# WIP: TSシークバーへのサムネイル表示 (Phase 2)

## 目的

Phase 1（[#43](https://github.com/daig0rian/epcltvapp/pull/43)、マージ済み）でTS再生のシーク機能を実装した。そのシーク点の一部にサムネイルを付与する。

## スパイク(AGP 9.0)の結果: 成功、本採用に進める

`FrameExtractor.setMediaSourceFactory()`(media3 1.10.0+)を使うためにcompileSdk 36が必要になり、
それにはAGP 9.0.1 + Gradle 9.1.0へのメジャーバージョンアップ(旧DSL/kotlin-androidプラグイン
非互換のBreaking Change付き)が必要と判明した。「このアプローチのノックアウト点を見つける」
目的のスパイクとして進めた結果、`android.newDsl=false` / `android.builtInKotlin=false`
(`gradle.properties`)でAGP 9.0の破壊的変更をオプトアウトしつつcompileSdk 36の解禁だけを
得る方針で**クリーンビルド成功・実機(Google TV Streamer)でのARIB字幕表示も正常動作を確認済み**。
ノックアウトされなかったため、このまま本採用として進める。

- ローカルSDKに「Android 16.0 (Baklava) API Level 36.0」(plain、36.1とは別)の追加インストールが必要だった。
- ビルドログに出た`.so files not available for arm64-v8a...`警告は、Android Studioの
  接続デバイス限定ビルド最適化によるもので、AGP変更前から存在する既知の挙動と判明(実害なし)。

## 確定した設計

`androidx.media3.inspector.frame.FrameExtractor`（ExoPlayer自身のTsExtractorでフレームをデコードするAPI、media3 1.10.0でmedia3-inspector-frameモジュールに分離）を使う。media3を1.10.1にアップグレード済み([#44](https://github.com/daig0rian/epcltvapp/pull/44)は1.9.2への上げだったが、Phase 2実装中にこの後さらに1.10.1へ再アップグレードした)。

### 1周目の設計は実機検証で否定された

当初は「FrameExtractorはtsreadexを経由せずExoPlayer内蔵の`TsExtractor`が持つPCRベースの二分探索シーク機構(`TsBinarySearchSeeker`)がそのまま使えるはずなので、独自のバイト位置計算は不要」という設計で実装したが、実機検証(ADB logcat)で**全シーク点が同一の先頭付近フレームしか返さない**不具合が発生した。

原因はmedia3のソース(`FrameExtractorInternal.PlayerListener.onPlaybackStateChanged`)のコメントで確認: 「If the seek resolves to the current position, the renderer position will not be reset and extractedFrameNeedsRendering remains false. No frames are rendered. Repeat the previously returned frame.」——ExoPlayer自身の内部`seekTo()`が、このアプリのARIB TSに対しては要求位置に関わらず常に「現在位置と同じ」と判定し、実質何もしていなかった。Content-Lengthは正しく取得できていることをcurlで直接確認済みなので、それが原因ではない。

### 2周目の設計: Phase 1の実績あるシーク機構を流用する

`FrameExtractor`の内部`seekTo()`には一切頼らず、Phase 1で確立済みの「シーク点ごとに、その概算バイト位置(`TsSeekDataProvider.estimateByteOffset`)から開始する`MediaSource`を都度新規に構築する」方式(`TsReadexDataSource.startByteOffset`)をサムネイル生成にも流用する。

- media3 1.10.0以降で`FrameExtractor.Builder.setMediaSourceFactory(MediaSource.Factory)`が追加された(1.9.xには無い)。これによりシーク点ごとに`TsReadexDataSource.Factory(dataSourceFactory).apply { startByteOffset = ...; nativeProcessingEnabled = false }`を包んだ`ProgressiveMediaSource.Factory`を注入できる。
- 各シーク点で**新しい`MediaItem`(mediaIdを点ごとに変える)+新しい`FrameExtractor`インスタンス**を作る。これによりFrameExtractor内部の「同一MediaItemなら`seekTo()`で済ませる」再利用判定(`needsPrepare`)を確実に不成立にし、都度フルの再準備(prepare)をさせる。
- `getFrame(ms)`ではなく`getThumbnail()`を使う。常に「そのオフセット済みストリームの先頭付近のいいフレーム」を返すヒューリスティックで、機能しない`seekTo()`経路を通らない。
- `nativeProcessingEnabled = false`(tsreadexネイティブフィルタ不使用)。サムネイルにARIB字幕・音声処理は不要なため、オーバーヘッドを避ける。

実機検証(30:04コンテンツ)で正しく動作: 400シーク点に対し11枚の実サムネイルが生成され、位置ごとに異なる内容になることを確認。

### 3周目の設計: 事前生成(BFS)をやめ、オンデマンド生成に切り替えた

実機確認で2点判明:

1. Leanbackは**表示中の全シークステップに対して`getThumbnail()`を呼び**、コールバックが来ていない間は直前に届いたBitmapを表示し続ける。つまり「疎な事前生成」で意図的にコールバックを呼ばなくても、Leanback側が自動的に間を埋めてくれる。1点のサムネイルが複数の隣接シークステップに「見える」のはLeanback自身の挙動であり、こちら側で明示的に複数キーへ同じBitmapを設定する必要はない(そもそもしていない)。
2. シークは常に先頭(index=0付近)から始まるが、旧BFS事前生成順序は中間点を優先するため、ユーザーが最初に目にする範囲にサムネイルが用意されているまでの時間が長すぎた。

この2点を踏まえ、**BFS事前生成を全廃しオンデマンド生成に変更**。`getThumbnail(index, callback)`が呼ばれた時点で該当indexの生成をその場で開始する(`PlaybackSeekDataProvider.getThumbnail`のjavadocが元々「on UI thread」「may start background thread and invoke callback later」を想定した設計であり、素直にこれに従う形)。副次効果として、`TsSeekDataProvider.startThumbnailGeneration()`(メインスレッド起動タイミング調整用に用意していたメソッド)も不要になった——Leanbackの`getThumbnail`呼び出し自体が元々UIスレッド契約なので、FrameExtractorのスレッド固定要件は自然に満たされる。

旧調査コード(`investigateTsreadexNormalizedThumbnail`等、MediaMetadataRetriever経由の検証一式)は削除済み。

### 認証について

このアプリの`buildOkHttpClient`はURLの`user:pass@host`を検出した場合のみBasic認証ヘッダーを付与する。`TsThumbnailGenerator`は自前で`OkHttpDataSource.Factory(httpClient)`を組み立てるため(`PlaybackVideoFragment`から渡された`httpClient`をそのまま使う)、この認証機構の恩恵をそのまま受けられる。

## 実装済み

- [x] `app/build.gradle` / `build.gradle` / `gradle.properties` / `gradle-wrapper.properties`: media3 1.10.1、AGP 9.0.1、Gradle 9.1.0、compileSdk 36
- [x] `TsThumbnailGenerator.kt`: `getThumbnail(index, callback)`呼び出し時にオンデマンドで、`TsReadexDataSource`(startByteOffset指定・ネイティブ処理オフ)を包む`MediaSource.Factory`を構築し`FrameExtractor.setMediaSourceFactory()`で注入、`getThumbnail()`で取得。`Map<Int, Bitmap>`キャッシュ
- [x] `TsSeekDataProvider.kt`: `httpClient`を追加受け取り、`estimateByteOffset`メソッド参照を`TsThumbnailGenerator`に渡す
- [x] `PlaybackVideoFragment.kt`: `TsSeekDataProvider`構築時に`client`を渡すよう変更

## 残タスク

- [ ] **オンデマンド生成版の実機動作確認待ち**: 最初のシークステップからサムネイルが揃うか、複数ステップを跨いだ表示が自然か
- [ ] 動作確認が取れ次第、`feature/ts-seek-thumbnails`ブランチのコミットを整理し、PR作成
- [ ] AGP 9.0/Gradle 9.1.0/compileSdk 36への引き上げをCLAUDE.mdの依存関係バージョン表に反映する(PR化のタイミングで)

## 重要な決定事項

- ビルドはAndroid Studioでユーザーが手動実行する(Claude Codeはgradleを叩かない)。
- コルーチン未導入のプロジェクトのため、既存パターン(Handler + バックグラウンドThread、Guavaの`ListenableFuture`+`Futures.addCallback`)を踏襲する。
- 実機での不具合調査はADB logcat経由で直接確認する(推測で進めない)。EPGStationサーバーへの直接curlでもContent-Length等のサーバー側挙動を確認できる。
