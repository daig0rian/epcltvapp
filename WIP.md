# WIP: レジューム再生 (feature/resume-playback)

## 目的

前回途中で再生を止めた録画を再度開いたとき、「前回再生を停止した位置から再開しますか？」を
半透明ダイアログで確認し、[レジューム再生] を選ぶと前回位置へシークする。
再生自体は選択によらず最初から開始する（ダイアログの裏で再生が始まっている）。

設定「レジューム再生」(再生設定・デフォルトON・内蔵プレーヤー選択時のみ表示) がONのときだけ
ダイアログを出す。

## 実装方針

- `PlaybackPositionStore` … 専用 SharedPreferences (`playback_positions`) に
  「動画キー -> 位置ms,更新時刻ms」を保存。件数上限を超えたら更新の古い順に捨てる。
  - キー: v1 は `v1:<recordedProgramId>:<actionId>` / v2 は `v2:<recordedItemId>:<actionId>`
- 保存タイミングは `PlaybackVideoFragment.onPause()`。
  - 10秒未満 → 何もしない（前回位置を消さない。ダイアログを見てすぐ戻った場合の保護）
  - 終端付近(残り15秒以内) → エントリ削除（最後まで見たので次は最初から）
  - それ以外 → 保存
- 「最初から」選択時は即座にエントリ削除する。
- 対象外: ライブ(HLS/mpegts)、HLS追いかけ再生(`IS_HLS`)。
- TSは `TsSeekDataProvider` の完成待ちが要るため `pendingTsResumePositionMs` に積んで
  `refreshTailAndSeekBar` 完了時に `performTsSeek()` する。
- 位置の保存/復元は設定OFFでも保存だけは続ける（ONに戻したとき使えるように）。ダイアログのみ設定で制御。

## 完了済み

- (作業中)

## 残タスク

- 実機での動作確認
