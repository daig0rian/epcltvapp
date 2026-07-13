# WIP: mpegts直送ライブ視聴（実験）

## 目的

ライブ視聴はHLSで実装済みだが、低遅延化のためmpegts直送を実験的に試す。
最終的にmpegts直送の方が低遅延・高画質にできると判明したため、
**単押し=mpegts直送（デフォルト）、長押し=HLS（字幕対応のフォールバック）**
という配置に落ち着いた（新規UIは増やしていない）。

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
- 【訂正】当初「`mode=2`の無変換（パススルー）は再生不可 → 端末がMPEG-2ハードウェア
  デコード非対応」と判断していたが誤りだった。当時ユーザーの`config.yml`の`m2ts`
  セクションは1080pプロファイル1件のみで、`mode=2`は単に配列の範囲外（存在しない
  インデックス）を指定していただけだった。「無変換」を実際に配列へ追加して`mode=0`に
  持ってきたところ、即座に再生開始し、画質も最良の結果になった。端末は実際には
  MPEG-2ハードウェアデコードに対応していた。
- ユーザーが`config.yml`の`m2ts`セクション`mode=0`を「無変換」（パススルー、
  再エンコードなし）に設定。結果:
  - 起動が最速（再エンコード工程が一切ないため。体感ほぼ即再生）
  - 画質は理論上最高（放送そのまま、再エンコードによる劣化なし）
  - サーバー側CPU負荷も最小（エンコード処理なし）
  - トレードオフは帯域（地上デジタル1080iは無変換だと15〜18Mbps程度になりうる）。
    自宅内LAN・AndroidTVでの利用のため問題なしと判断。
  - H.264変換プロファイル（1080p/10Mbps、`-tune zerolatency -g 30 -keyint_min 30`
    でGOP短縮・低遅延化）は、帯域が厳しい環境向けの代替案として別途検証済み
    （どちらも動作確認済みだが、現状は無変換を採用）。

## 決定事項

- 生のMPEG-TSバイトストリームだが、**字幕処理は使わない**（クラッシュのため）。
  `startDirectPlayback(url, httpClient, isTsContent=false)` で、ExoPlayer標準の
  `ProgressiveMediaSource` + `OkHttpDataSource` のみで再生する。
- トリガーは**単押し=mpegts直送（デフォルト）、長押し=HLS**（従来はこの逆だったが、
  mpegts優位が判明したため入れ替えた）。
- URL: `EpgStationV2.getLiveMpegTsUrl(channelId, mode=0)` = `baseUrl + "streams/live/$channelId/m2ts?mode=$mode"`
  （Retrofit API呼び出し不要、URL文字列を組み立てるだけ）。`mode=0`はサーバー側の
  `config.yml`次第（ユーザー環境では現在「無変換」＝パススルーに設定済み）。
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
- [x] 実機確認OK（ユーザー確認済み）
  - mpegts直送: 体感5秒程度で表示（HLSの約25秒より大幅に低遅延）。
    H.264変換1080p/10Mbpsで画質もHLS版と同等に。
  - さらに`config.yml`側`mode=0`を「無変換」（パススルー）にしたところ、
    ほぼ即再生・画質も理論上最高という結果に
  - 単押し=mpegts / 長押し=HLS への入れ替え後も問題なく動作

## 既知の制約

- mpegts直送版はARIB字幕・文字スーパー非対応（ネイティブクラッシュのため無効化）。
  字幕が必要な場合は長押しでHLS版にフォールバックできる。
- 画質・起動速度はEPGStationサーバー側の`config.yml`の`stream.live.ts.m2ts`
  `mode=0`の設定次第。無変換（パススルー）が最速・最高画質だが帯域を多く使う
  （地上デジタル1080iで15〜18Mbps程度）。帯域が厳しい環境ではH.264変換
  プロファイル（1080p/10Mbps程度、`-tune zerolatency -g 30 -keyint_min 30`推奨）
  に切り替えるとよい。
- 無変換（パススルー）は稀に一部チャンネルで再生できないことがある
  （放送波の構造差によるものと推測、詳細未調査）。ユーザー運用では
  そのチャンネルだけ長押しでHLS版にフォールバックすれば問題なく視聴できており、
  単押し=mpegts・長押し=HLSの二段構え設計が実際に機能することを確認済み。

## 残タスク

- [ ] コミット・PR作成
