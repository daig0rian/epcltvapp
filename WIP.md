# WIP: ライブ視聴の番組表更新の自動化 (issue #36)

## 目的
現状「番組名更新」ボタン（手動）でしか更新されないライブ視聴チャンネルの現在放送番組名を、
1分ごとのタイマーで自動的にAPIから取得・反映する。
（当初issue本文では5分間隔の案だったが、3分枠の短い番組の取りこぼしを避けるためユーザー指示で1分間隔に変更）

参照: https://github.com/daig0rian/epcltvapp/issues/36

## 前提となった経緯（重要）
セッション開始時、作業ブランチが `upstream-pr/live-viewing`（imaiworks氏によるPR #32のレビュー用ブランチ）
になっていた。確認したところ PR #32 は既に squash merge 済み（origin/master に取り込み済み、
コミット `5d95d18`）で、ローカル `master` は1コミット遅れていただけだった。
そのため以下の後片付けを実施した:
- `git checkout master && git pull origin master` でローカルを最新化
- `upstream-pr/live-viewing` ブランチを削除（squash mergeのためコード上git是merged判定されず `-D` で強制削除。
  ツリー差分を比較し、コード面の差分がないことを確認済み）
- 本ブランチ `feature/live-program-auto-refresh` を更新後の master から新規作成

## 現状実装の理解
- `MainFragment.kt` の `updateRows()` (L317〜) がライブチャンネル一覧 (`Category.LIVE_CHANNELS`) を
  `EpgStationV2.api.getChannels()` で取得し、既存アダプタと差分（追加/削除）を反映。
  ただし `ChannelItem.currentProgramName` (`@Transient`, equals対象外) はここでは設定されない。
- 「番組名更新」ボタン（`SettingsCardPresenter.Item.Action.REFRESH_PROGRAM_NAMES`）押下時のみ、
  `refreshLiveProgramNames()` (L586〜) が `EpgStationV2.api.getScheduleOnAir()` を呼び、
  チャンネルIDをキーに現在番組名を突き合わせて各 `ChannelItem.currentProgramName` を書き換え、
  `notifyArrayItemRangeChanged()` でカードに反映している。
- ポーリングの前例: `PlaybackVideoFragment.kt` の `keepAliveHandler`
  （自己再スケジュール型 `Handler.postDelayed` Runnable、`onDestroy`等で `removeCallbacks`）。
  これをテンプレートとして流用する。
- 衝突リスク: `refreshLiveProgramNames()` は呼び出し時点のアダプタ参照をクロージャで保持しており、
  レスポンス到達までの間に `reloadContentRows()` 等で行のアダプタが再生成されていると、
  古いアダプタに対して無意味な更新をしてしまう（クラッシュはしないが反映漏れ）。

## 完了したこと
- [x] issue #36 の内容確認
- [x] 現状実装の調査（Explore agentで実施）
- [x] ブランチ整理（上記）・本ブランチ作成

## 残タスク
- [x] 1分間隔の自動更新タイマーを実装（`Handler.postDelayed` 自己再スケジュール方式、`mProgramNameAutoRefreshRunnable`）
- [x] `onResume`/`onPause` にタイマーの開始・停止を紐付け（非表示中は動かさない）
- [x] `refreshLiveProgramNames()` をレスポンス到達時に最新アダプタを取り直す方式に変更（衝突対策）
- [x] チャンネル一覧初回表示時（`updateRows()` 内）にも即座に番組名を反映
- [x] 手動「番組名更新」ボタンを削除（ユーザー指示。自動更新で不要と判断）
- [ ] ビルド確認をユーザーに依頼（Android Studioで手動ビルド）
- [ ] 動作確認後にPR作成

## 実装内容サマリ
`MainFragment.kt` を変更:
- `PROGRAM_NAME_AUTO_REFRESH_INTERVAL_MS = 1分` を追加
- `mProgramNameAutoRefreshRunnable`: 自己再スケジュール型Runnable。`refreshLiveProgramNames()`を呼んだ後、自身を1分後に再postDelayed
- `startProgramNameAutoRefresh()`/`stopProgramNameAutoRefresh()`: 既存の`mHandler`を使い回してpostDelayed/removeCallbacks。`onResume()`末尾で開始、`onPause()`冒頭で停止
- `updateRows()`のチャンネル一覧取得成功時、末尾で`refreshLiveProgramNames()`を即時呼び出し（番組名の初期反映がタイマー待ちにならないように）
- `refreshLiveProgramNames()`: レスポンス到達時に`mMainMenuAdapter.getListRowByHeaderId()`でアダプタを取り直すよう変更（呼び出し時点でクロージャ捕捉していたものを変更、行の入れ替わりへの耐性向上）。エラーToastは出さない（自動実行のみになったため）
- 手動「番組名更新」ボタンを削除: `Category.PROGRAM_NAME_REFRESH`、`loadRows()`内の行作成、クリックハンドラ分岐、`SettingsCardPresenter.Item.Action.REFRESH_PROGRAM_NAMES`、`sidebarIconMap`のエントリ、`strings.xml`/`values-ja-rJP/strings.xml`の`refresh_program_names`文字列を全て除去

## 重要な決定事項
- 手動更新ボタンは削除する（ユーザー指示：自動更新があれば不要）。自動タイマーのみで運用。
- タイマー間隔は1分（60,000ms）固定。3分枠の短時間番組でも取りこぼさないようユーザー指示で変更（issue本文の5分案から短縮）。設定画面での可変化は今回のスコープ外。
