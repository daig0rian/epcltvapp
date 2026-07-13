# WIP: mpegts直送ライブ視聴（実験）

## 目的

ライブ視聴はHLSで実装済みだが、低遅延化のためmpegts直送を実験的に試す。
既存のHLS視聴（チャンネルカードのタップ）はそのまま残し、**長押し**で
mpegts直送再生を試せるようにする（新規UIを増やさない）。

## 新発見（重要）

- EPGStation本家ソース (`src/model/service/api/streams/live/{channelId}/m2ts.ts`,
  `client/src/views/WatchOnAir.vue`) を確認したところ、`GET /streams/live/{channelId}/m2ts?mode=0`
  は **HLSと違って開始APIが不要**。プレイヤーが直接このURLに接続するだけで配信開始、
  切断で自動終了する、TCP接続に紐づいた単純な仕組み。streamId取得・keep-alive・停止の
  API呼び出しは一切不要（以前のセッションで「HLSと同じ複雑さ」と説明していたのは誤りで、
  サーバー側のサービス層インタフェースだけを見て判断していたため。実際のRESTルートは
  もっとシンプルだった）。
- 実機検証の結果、**字幕処理のネイティブコード（`TsReadexDataSource`/ARIB字幕）を
  生のライブTSに使うとクラッシュする**ことが判明。録画ファイルと違い、生放送のTSは
  複数サービス多重化やPAT/PMTのタイミングが異なるため、ネイティブ側が想定しない
  構造に当たると考えられる。→ **mpegts直送では字幕処理を通さない**方針に変更。
- `mode` パラメータの意味はHLSとmpegtsで独立（EPGStationの`config.yml`で別々に
  プロファイルを定義するため）。デフォルトの`m2ts` `mode=0`は720p/3Mbps、
  ユーザーの`hls` `mode=0`は1080p/10Mbpsで、画質差の原因だった。
- `mode=2`（デフォルト設定の「無変換」＝パススルー）は再生不可だった。地上デジタル放送の
  映像コーデックはMPEG-2で、多くの安価なAndroid TV機器（Fire TV Stick等）は
  ライセンス費用の関係でMPEG-2ハードウェアデコーダーを持たないため、と推測。
  → 無変換は諦め、H.264変換プロファイルを使う方針に確定。
- ユーザーが`config.yml`の`m2ts`セクションに、`hls`の1080p/10Mbps設定と同等の
  H.264変換プロファイルを`mode=0`として追加し、画質・遅延ともに良好な結果を確認。

## 決定事項

- 生のMPEG-TSバイトストリームだが、**字幕処理は使わない**（クラッシュのため）。
  `startDirectPlayback(url, httpClient, isTsContent=false)` で、ExoPlayer標準の
  `ProgressiveMediaSource` + `OkHttpDataSource` のみで再生する。
- トリガーは「現在放送中」行のチャンネルカードの**長押し**（従来未使用のジェスチャー）。
  通常タップは従来通りHLS。
- URL: `EpgStationV2.getLiveMpegTsUrl(channelId, mode=0)` = `baseUrl + "streams/live/$channelId/m2ts?mode=$mode"`
  （Retrofit API呼び出し不要、URL文字列を組み立てるだけ）。`mode=0`はサーバー側で
  H.264変換プロファイルが設定されている前提（ユーザー環境では1080p/10Mbpsに設定済み）。
- 録画ボタン(REC)・シーク無効化などのライブ視聴UIはHLS版と共通化
  （`isLive || isLiveMpegTs` で判定）。
- USBデバッグが使えない環境でのクラッシュ調査用に、`EpgTvApplication.kt`
  （未捕捉例外を内部ストレージに保存し、次回起動時に`MainFragment`がダイアログ表示）を
  新規追加。ただしネイティブクラッシュ（今回のケース）は捕捉できない制約あり
  （Kotlin例外ではなくOSレベルで即死するため）。

## 完了

- [x] ブランチ作成 (`feature/mpegts-live-experiment`、master から分岐)
- [x] EPGStation本家ソース調査でmpegts直送APIの実際の仕様を確認
- [x] `EpgStationV2.kt` に `getLiveMpegTsUrl()` を追加
- [x] `DetailsActivity.kt` に `IS_LIVE_MPEGTS` extra キーを追加
- [x] `OriginalCardPresenter.kt` の `ChannelItem` 長押しでmpegts再生Intentを起動
- [x] `PlaybackVideoFragment.kt` にmpegts直送再生分岐を追加（字幕処理なし、
      `isLive || isLiveMpegTs` でREC/シーク無効化を共通化）
- [x] `EpgTvApplication.kt` 新規追加（クラッシュログの内部保存・次回起動時表示）
- [x] `assembleDebug` ビルド成功確認
- [x] 実機確認OK（ユーザー確認済み: 「きれいに見えるようになったよ！」）
  - HLS版タップは従来通り動作
  - 長押しでmpegts直送再生、体感5秒程度で表示（HLSの約25秒より大幅に低遅延）
  - サーバー側`m2ts`に1080p/10Mbpsプロファイルを追加後、画質もHLS版と同等に

## 既知の制約

- mpegts直送版はARIB字幕・文字スーパー非対応（ネイティブクラッシュのため無効化）
- 動作にはEPGStationサーバー側の`config.yml`の`stream.live.ts.m2ts`に、
  H.264変換プロファイルが`mode=0`として設定されている必要がある
  （デフォルト設定のままだと720p/3Mbpsと低画質、`mode=2`の無変換は再生不可）

## 残タスク

- [ ] コミット・PR作成
