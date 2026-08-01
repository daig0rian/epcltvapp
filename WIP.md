# WIP: Issue #42 収録中TSの追いかけ再生（HLS非依存）

## 目的

Issue #42。収録中TSを内蔵プレーヤーで直接再生している最中、収録が続いていてもファイル終端(STATE_ENDED)で
止まってしまう問題を解消する。既存の「シーク時にMediaSourceを開き直す」機構(PR #43)をそのまま流用し、
STATE_ENDED時に今の終端(安全マージン込み)へ疑似シークすることで追いかけ再生を実現する。

設計の詳細・時系列の根拠は以下のプランファイル参照:
`C:\Users\daigo\.claude\plans\magical-wandering-seal.md`

### 正しい時系列（Issue #42コメントの誤りを修正）

1. ① 再生開始 or シーク完了時: 収録中かどうかを再確認し、シークバーの終端時刻・ステップを更新する。
2. ② Player.STATE_ENDED検知
3. ③ ①で収録中だった場合: 今のEnd時刻(安全マージン込み)へシークする。
4. ④ シークにより開き直されるので①が再度走る。

## 完了済み

- [x] 設計プラン確定（ユーザーレビューで2点修正: tail直の代わりにperformTsSeek経由の安全マージン化、
      マージンをSEEK_POINT_INTERVAL_MS(15秒)ではなくTS_CATCHUP_SAFETY_MARGIN_MS(2秒、日本の放送の
      キーフレーム間隔目安)に分離）
- [x] ブランチ作成

## 残タスク

- [x] `PlaybackVideoFragment.kt` 実装
  - [x] 新規フィールド (`tsCatchUpActive` / `tsCatchUpRecordedProgramId` / `tsCatchUpRecordedItemId` / `tsHeadPoint`)
  - [x] `refreshTailAndSeekBar()` 切り出し + `startTsProbing()` 簡略化
  - [x] `refreshCatchUpRecordingStatus()` (v1: `getRecorded(recording=true)` / v2: `getRecording()`)
  - [x] `restartTsPlaybackAt()` 末尾に①の再トリガーを追加
  - [x] STATE_ENDEDハンドラで`performTsSeek(durationMs - TS_CATCHUP_SAFETY_MARGIN_MS)`
  - [x] `TS_CATCHUP_SAFETY_MARGIN_MS = 2_000L` 定数追加
- [ ] ユーザーによる実機動作確認（収録中TS追いかけ再生・手動シーク時の更新・収録終了後の従来動作・回帰無し）
- [ ] 動作確認OK後、WIP.md削除 + コミット → PR作成

## 重要な決定事項

- TsProbe.kt / TsSeekDataProvider.kt / TsSeekPlayerAdapter.kt は変更しない（既存API再利用のみ）。
- 再接続が短時間に連続しうる問題（バックオフ等）は今回のスコープ外。まず動かして実機で問題が出るか確認する。
- v1(`recordedProgram`)・v2(`recordedItem`)両方のTS直接再生経路に対応する。
