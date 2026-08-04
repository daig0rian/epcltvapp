# WIP: fix/caption-timing-on-pause

## 目的

TS再生中に字幕表示状態で一時停止すると、一時停止中にもかかわらずその先のセリフの字幕が
2個、3個と順に表示されてしまう問題を修正する。

## 原因

字幕の表示/消去タイミングが**壁時計 (Handler.postDelayed)** で管理されていたため、
再生が止まっても字幕のスケジュールだけが進み続けていた。

1. `PlaybackVideoFragment.scheduleWithBufferDelay()`
   字幕PESはロード時(=再生位置より先)に解析されるため、解析時点の
   `bufferedPosition - currentPosition` を遅延量として `postDelayed` していた。
   一時停止してもこのタイマーは走り続け、先の字幕が次々に表示される。
2. `SubtitleOverlayView.showCaptions()`
   表示継続時間 (`durationMs`) の経過による自動消去も `postDelayed`。
   一時停止中に字幕が勝手に消えてしまう(画面を読みたくて止めた場合に不都合)。

## 対応

タイミング管理を壁時計から**再生位置 (`ExoPlayer.currentPosition`)** ベースへ変更した。

- `SubtitleOverlayView` から自前のタイマーを撤去し、純粋な描画ビューにした。
- `PlaybackVideoFragment` に「表示すべき再生位置」付きの待ち行列
  (`pendingCaptions` / `pendingSuperimposes`) を導入。
  表示位置は従来の遅延計算と同じ根拠で、PES解析時点の `bufferedPosition`。
- 100ms周期のティッカー (`onCaptionTick`) が再生位置を見て、表示位置に達したものを描画し、
  消去予定位置 (`captionExpiryPositionMs` / `superimposeExpiryPositionMs`) を過ぎたら消す。
  一時停止中は再生位置が進まないため、先走り表示も勝手な消去も起きない。
- シーク時 (`restartTsPlaybackAt`)・字幕オフ時・`onDestroyView` で待ち行列をリセット。

## 完了済み

- [x] 実装

## 残タスク

- [ ] ユーザーによるビルド・実機動作確認

## 動作確認項目

- 字幕ON・TS再生中に一時停止 → 表示中の字幕がそのまま残り、先のセリフが出てこない
- 一時停止解除 → 字幕が正しいタイミングで続きから表示される
- 通常再生中の字幕タイミングが従来どおり(早すぎ/遅すぎがない)
- シーク後に古い字幕が残らない
- 字幕ON/OFFトグルが即座に効く
- 文字スーパーも同様に動作する
- 追いかけ再生(収録中TS)で字幕が出続ける
