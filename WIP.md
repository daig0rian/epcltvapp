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

## 残タスク

- [ ] エミュレーター/実機での動作確認をユーザーに依頼
  - 左メニューに「現在放送中」の下に「番組名更新」行が出るか
  - 「番組名更新」を押すとチャンネルカードに番組名が表示されるか
  - 番組名が長い場合の表示崩れがないか
- [ ] 動作確認OK後にコミット・追加プッシュ
