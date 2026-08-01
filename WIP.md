# WIP: TSシークバーへのサムネイル表示 (Phase 2)

## 目的

Phase 1（[#43](https://github.com/daig0rian/epcltvapp/pull/43)、マージ済み）でTS再生のシーク機能を実装した。そのシーク点の一部にサムネイルを付与する。

## 確定した設計

media3を1.9.2にアップグレード済み([#44](https://github.com/daig0rian/epcltvapp/pull/44))なので、`androidx.media3.inspector.FrameExtractor`（ExoPlayer自身のTsExtractorでフレームをデコードするAPI）を使う。

**重要な発見**: `FrameExtractor.Builder`はカスタム`DataSource.Factory`を注入できず(`Context`と`MediaItem`のみ)、内部で`DefaultMediaSourceFactory`+標準HTTPデータソースを直接使う。つまりtsreadexを一切経由しない。ExoPlayer組み込みの`TsExtractor`はファイル先頭・末尾のPCRを軽量に読んで概算durationを求める`TsDurationReader`と、そこからPCRベースの二分探索でシークする`TsBinarySearchSeeker`を元々持っている（media3 1.3.1時点から存在、ソース確認済み・今回のアップグレードで新規に得た能力ではない）。`TsReadexDataSource`が`C.LENGTH_UNSET`を返して意図的にこれを無効化しているのは、tsreadexがステートフルなフィルタでランダムシークに耐えられないため。`FrameExtractor`はtsreadexを経由しないのでこの制約自体が関係なく、組み込みの効率的なシーク機構がそのまま使える。

**→ 独自のバイト位置計算は不要。`TsSeekDataProvider`が既に持つ相対時刻(ms)をそのまま`FrameExtractor.getFrame(ms)`に渡すだけでよい。**

旧調査コード(`investigateTsreadexNormalizedThumbnail`, `dumpPmtHex`, `ThumbnailInvestigationDataSource`, `THUMBNAIL_PROBE_RAW_BYTES`)は削除済み。MediaMetadataRetrieverが生ARIB TSを扱えない件の調査結果(CA_descriptor起因、詳細は削除済み旧WIPおよびgit historyのdocsコミット参照)は、FrameExtractor採用によりそもそも無関係になった。

### API上の注意(1.9.2時点)

`androidx.media3:media3-inspector:1.9.2`で`androidx.media3.inspector.FrameExtractor`。**1.10.0で`media3-inspector-frame`モジュールに分離され`androidx.media3.inspector.frame.FrameExtractor`にパッケージが変わる破壊的変更がある**(Web上の最新ドキュメントは1.10系向けなので鵜呑みにしないこと)。将来1.10以降へ上げる際はこの移行が必要。

### 認証について

このアプリの`buildOkHttpClient`はURLの`user:pass@host`を検出した場合のみBasic認証ヘッダーを付与するが、`FrameExtractor`はこのクライアントを経由できない。ユーザー確認により現状Basic認証は未使用のため、素のURLをそのまま`MediaItem.fromUri()`に渡す設計とした。将来Basic認証を使う場合は別途対応が必要(ローカル認証プロキシを立てる等)。

## 実装済み

- [x] `app/build.gradle`: `media3-inspector`依存追加
- [x] `TsThumbnailGenerator.kt`(新規): FrameExtractorのライフサイクル管理、二分木BFS順での1-in-10点サムネイル事前生成、`Map<Int, Bitmap>`キャッシュ、`PlaybackSeekDataProvider.ResultCallback`への委譲
- [x] `TsSeekDataProvider.kt`: `context`/`videoUrl`を受け取り`TsThumbnailGenerator`を保持。`getThumbnail()`/`reset()`をオーバーライドして委譲。`startThumbnailGeneration()`(メインスレッドから呼ぶ必要があるため`TsSeekDataProvider`構築とは分離)、`release()`を追加
- [x] `PlaybackVideoFragment.kt`: `startTsProbing()`で`TsSeekDataProvider`にcontext/urlを渡すよう変更。`mainHandler.post`内(メインスレッド)で`startThumbnailGeneration()`を呼ぶ。`onDestroyView()`で`tsSeekDataProvider?.release()`を追加。旧調査コード一式を削除(未使用importの`MediaMetadataRetriever`/`okhttp3.Request`も削除)

### スレッド安全性についての注記

`FrameExtractor`は「単一スレッドからのみアクセスする」契約がある。`TsSeekDataProvider`自体はバックグラウンド(`tsProbeExecutor`)で構築されるが、`TsThumbnailGenerator.start()`（初回の`FrameExtractor`構築+`getFrame()`呼び出しを含む）は`PlaybackVideoFragment`側の`mainHandler.post`内で明示的に呼ぶことで、以降のコールバック(`ContextCompat.getMainExecutor`経由でメインスレッド固定)とスレッドを一貫させている。

## 残タスク

- [ ] **ビルド・実機動作確認待ち**: サムネイルが実際に表示されるか、シーク中のパフォーマンスに問題がないか
- [ ] UI側(シークバーのサムネイル表示)が期待通り動くかの確認 — Leanback側の表示自体はフレームワーク任せなので、`getThumbnail`が正しく呼ばれてBitmapが返っていれば自動的に表示されるはず
- [ ] 動作確認が取れ次第、`feature/ts-seek-thumbnails`ブランチのコミットを整理(調査過程の`wip:`コミット群は残しても squash merge されるため問題ないが、必要なら整理を検討)し、PR作成

## 重要な決定事項

- ビルドはAndroid Studioでユーザーが手動実行する(Claude Codeはgradleを叩かない)。
- コルーチン未導入のプロジェクトのため、既存パターン(Handler + バックグラウンドThread、Guavaの`ListenableFuture`+`Futures.addCallback`)を踏襲する。
- サムネイルは全シーク点ではなく1-in-10点のみ生成する。対象外の点は`getThumbnail`のコールバックを呼ばないことで「サムネイル無し」を表現する(Leanback側もコールバック未呼び出しを許容する設計、leanbackソースで契約確認済み)。
