# WIP: ライブ視聴（現在放送中のチャンネル）対応

## 目的

録画済み番組の視聴のみだったこのアプリに、現在放送中のチャンネルをライブ視聴できる機能を追加する。
EPGStation の HLS ライブ配信 API を利用する（mpegts直送ではなくHLSを採用。理由は下記「決定事項」）。

スコープ：現在放送中の視聴のみ。番組表(EPG グリッド)は対象外。

## 決定事項

- **配信方式は HLS**。EPGStation サーバー側では mpegts も HLS もセッション管理
  （`streamId` 取得 → keep-alive → stop）は同じ複雑さ（`IStreamApiModel.ts` で確認済み）。
  違いは再生側で、HLS はこのアプリの録画「追いかけ再生」機能とほぼ同じ構造で実装済みの
  パターンを流用できるため、まず HLS で実装する。mpegts 低遅延版は将来の拡張候補。
- ライブ配信 API: `GET /streams/live/{channelId}/hls?mode={mode}` → `{ streamId }`
  （EPGStation 本家リポジトリ `client/src/model/api/streams/StreamApiModel.ts` で確認）。
  m3u8 URL 生成・stop・keep-alive は既存の `EpgStationV2.getHlsStreamUrl()` /
  `stopStream()` / `keepStream()` をそのまま流用可能。
- チャンネル一覧行は `MainFragment` の `Category` enum に `LIVE_CHANNELS` を先頭に追加し、
  `channels` API から取得したチャンネル一覧をカード表示。クリックで `DetailsActivity` を
  経由せず直接 `PlaybackActivity` を起動する（ライブには詳細画面に出す情報がないため）。

## 完了

- [x] ブランチ作成 (`feature/live-viewing-hls`)
- [x] EPGStation 本家のライブ配信 API 仕様調査

## 完了（続き）

- [x] `EpgStationV2.kt` に `startLiveHlsStream(channelId, mode)` を追加
- [x] `DetailsActivity.kt` に `IS_LIVE` / `CHANNEL_ID` / `CHANNEL_NAME` extra キーを追加
- [x] `PlaybackVideoFragment.kt` にライブ HLS 再生分岐 (`startLiveHlsPlayback`) を追加
- [x] `MainFragment.kt`
  - [x] `Category.LIVE_CHANNELS` 追加（先頭、セクション見出しなし）
  - [x] `updateRows()` にチャンネル一覧取得・行構築を追加
  - [x] `reloadContentRows()` の対象に追加
  - [x] `sidebarIconMap` にアイコン追加
  - [x] `ItemViewClickedListener` に `ChannelItem` クリック時の遷移を追加
- [x] `OriginalCardPresenter.kt` に `ChannelItem` のカード表示分岐を追加（長押し削除は対象外にした）
- [x] `ic_sidebar_live.xml` アイコン追加
- [x] `strings.xml` / `values-ja-rJP/strings.xml` に `live_channels` 文字列追加
- [x] `assembleDebug` ビルド成功確認（追加コードに警告・エラーなし）
  - 注: ビルドには `git submodule update --init --recursive`（`libaribcaption` / `tsreadex`）と
    `local.properties` の `sdk.dir` 設定が必要だった（このマシンでは初回ビルドのため未設定だった）。

## 完了（続き2: 番組名更新機能）

ユーザーからの追加要望：ライブ視聴で放送中の番組名も表示したい（リアルタイム自動更新は不要、
手動更新でよい）。左メニューに「現在放送中」とは別の独立行「番組名更新」を新設する方式を選択。

- [x] `ChannelItem.kt` に `@Transient var currentProgramName` を追加（equals/hashCodeには影響させない）
- [x] `Schedule.kt` 新規作成（`Schedule` / `ScheduleChannelItem` / `ScheduleProgramItem`）
- [x] `EpgStationV2.kt` に `getScheduleOnAir()` (`GET schedules/broadcasting`) を追加
- [x] `SettingsCardPresenter.kt` の `Action` enum に `REFRESH_PROGRAM_NAMES` を追加
- [x] `OriginalCardPresenter.kt` のチャンネルカードで `currentProgramName` を表示
- [x] `MainFragment.kt`
  - [x] `Category.PROGRAM_NAME_REFRESH` を `LIVE_CHANNELS` の直後に追加（区切り線なし）
  - [x] `loadRows()` に単体アクション行（🔄番組名更新カード1枚）を追加
  - [x] `sidebarIconMap` にアイコン追加（`ic_settings_reload` 再利用）
  - [x] `ItemViewClickedListener` に `REFRESH_PROGRAM_NAMES` クリック時の処理を追加
  - [x] `refreshLiveProgramNames()`: `/schedules/broadcasting` を叩き、`LIVE_CHANNELS` 行の
        各カードの `currentProgramName` を更新して `notifyArrayItemRangeChanged` で再描画
- [x] `strings.xml` / `values-ja-rJP/strings.xml` に `refresh_program_names` 追加
- [x] `assembleDebug` ビルド成功確認

## 完了（続き4: ライブHLSリトライ強化）

「再生→勝手に停止、20秒くらい待つと再生できる」というユーザー報告への対応。
配信開始直後はEPGStation側のffmpegウォームアップで404以外のエラーも起こりうるため、
`startLiveHlsPlayback()` のリトライ対象を404限定から種別不問・20回×2秒（最大40秒）に拡大。
録画の追いかけ再生(`startHlsPlayback`)側は変更していない。

- [x] `PlaybackVideoFragment.kt`: `startLiveHlsPlayback()` のリトライ方針を広げた
- [x] `assembleDebug` ビルド成功確認

## 完了（続き3: ライブ視聴画面に録画ボタン追加）

ユーザー要望：視聴画面から今見ているライブ番組をワンボタンで録画予約したい。
EPGStationの手動予約API (`POST /reserves`) を使用。録画は「ボタンを押した瞬間」からの
録画になる（生放送は巻き戻せないため番組頭は録れない）ことをユーザーに説明し合意済み。

- [x] `ManualReserveOption.kt` 新規作成（`programId` / `allowEndLack`）
- [x] `EpgStationV2.kt` に `addReserve()` (`POST reserves`) を追加
- [x] `PlaybackVideoFragment.kt`
  - [x] `liveChannelId` をフィールド化（onCreateのローカルvalから変更）
  - [x] `MyPlaybackTransportControlGlue` に `isLive` パラメータと `recordAction`（"REC"）を追加。
        ライブ視聴時のみ再生コントロールに録画ボタンを表示
  - [x] `startRecordingCurrentProgram()`: `/schedules/broadcasting` で現在の番組IDを取得し
        `addReserve()` で予約。押下中は "REC..." 表示、完了後 "REC" に戻す
        (`resetRecordActionLabel()`)
- [x] `strings.xml` / `values-ja-rJP/strings.xml` に `record_reserved` / `record_failed` 追加
- [x] `assembleDebug` ビルド成功確認

### 実機デバッグで踏んだ罠（記録）

1. **録画ボタンが表示されない**: `Action(id, label1)` のようにテキストのみで
   アイコンなしの `Action` は、この端末環境ではPlaybackControlsRowに描画されなかった。
   既存の `ic_sidebar_rec.xml` を `getContext()?.getDrawable(...)` でアイコンとして
   渡す4引数コンストラクタに変更したら表示された。
2. **「予約通信失敗」なのにEPGStation側では予約が成立していた**: `addReserve()` の
   戻り値を `Call<Long>`（レスポンスボディを素のJSON数値としてパース）で宣言していたのが
   原因。実際のレスポンス形と一致せずGsonパース例外が発生し、通信自体は成功していても
   `onFailure` に流れていた。`Call<okhttp3.ResponseBody>` に変更し、ボディをパースせず
   HTTPステータスだけで成功/失敗を判定するようにして解決。
   （2回目に本物の500が出たのは、1回目で予約が実際には成立していたため同じ番組を
   二重予約しようとしてサーバー側が本当にエラーを返したため）
3. **logcatが取れないSTB環境向けに `showDebugToast()` を追加**: 同一メッセージを
   `Toast.LENGTH_LONG` で3回連続表示するだけの簡易デバッグ手段。エラー系の分岐にのみ使用。
   今後同様の切り分けが必要な場面で流用できる。

## 動作確認済み（実機STBで確認、2026-07-12）

- [x] ライブ視聴（HLS）の再生
- [x] ライブ視聴画面の「REC」ボタン → EPGStation側に録画予約が作成される

## 未確認（コード上は実装・ビルド確認済みだが実機での明示確認はまだ）

- [ ] 起動直後のリトライ強化により「勝手に停止」が解消されたか
- [ ] 「番組名更新」ボタンでチャンネルカードに番組名が表示されるか

## 残タスク

- [ ] このセッションの変更をコミット・追加プッシュ（実施中）
- [ ] 一区切りついたら `gh pr create` へ誘導（前回案内したURLはまだ有効）
