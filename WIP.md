# WIP: ライブ視聴カードの表示改善とチラつき解消

## 目的
1. ライブ視聴カードが1分ごとに一律更新されることで、番組終了時刻を迎えていないカードまで再描画されチラつく問題を解消する。
   → 番組の終了時刻がわかっているので、カードごと（実装上は次に終了する番組の時刻）にタイマーを仕掛け、実際に終了時刻を過ぎたチャンネルだけ更新・再描画する。
2. ライブ視聴カードに番組の開始〜終了時刻が表示されていない問題を解消する。
   → カードのテキストを「チャンネル名 / 開始〜終了時刻（0埋め24時間表示） / 番組名」の3行にする。
## 完了済み
- [x] `ChannelItem.kt` に `currentProgramStartAt` / `currentProgramEndAt` を追加（`currentProgramName` と同様、equals/hashCodeに含めないTransient副次状態）
- [x] `MainFragment.kt`
  - [x] 固定1分間隔の `mProgramNameAutoRefreshRunnable` を廃止し `mProgramRefreshRunnable` に置き換え
  - [x] `refreshLiveProgramNames()` を、取得結果と現在の状態（`currentProgramStartAt`）を比較して「実際に番組が変わったチャンネルだけ」`notifyArrayItemRangeChanged` するように変更
  - [x] 次回実行を、表示中チャンネルの中で最も早く終了する番組の終了時刻（+5秒バッファ）に合わせて `postDelayed` するように変更（`scheduleNextProgramRefresh`）
  - [x] 終了時刻が不明な場合のフォールバック間隔（60秒）を用意
  - [x] onResume で `refreshLiveProgramNames()` を直接呼ぶように変更（最新情報取得+タイマー再スケジュールを兼ねる）、onPauseで `mHandler.removeCallbacks` するように変更
- [x] `OriginalCardPresenter.kt` の `is ChannelItem ->` 分岐で `contentText` を「開始〜終了時刻\n番組名」の2行にする（`titleText` は既存通りチャンネル名なので、全体で3行構成）
  - `formatTimeRange()` で `HH:mm〜HH:mm`（0埋め24時間表示）を生成

## 残タスク
- [ ] Android Studio でビルド・実機/エミュレーターでの動作確認
  - [ ] カードに開始〜終了時刻が正しく（0埋め24時間表示で）表示されるか
  - [ ] 1分ごとに全カードが点滅しないか（該当チャンネルの番組が変わった時だけ更新されるか）
  - [ ] 番組終了時刻をまたいだ際、該当カードだけがきちんと更新されるか
- [ ] 動作確認OKならコミット → PR作成

## 重要な決定事項
- EPGStation V2 API には全チャンネル一括の `schedules/broadcasting` しかなく、チャンネル単位の取得APIはない。
  そのため「カードごとのタイマー」は、内部的には「次に番組が終了するチャンネルの時刻に単一タイマーを合わせ、発火時に一括取得はするが、実際に番組が変わったチャンネルだけ再描画する」という実装にした。

## 取り消した試み
- 番組進捗バー（ヒーロー画像下端5%に濃淡2色のバーを表示する案）を実装したが、うまく動かなかったため取り消した。
  ImageCardViewの `main_image` を縮めてバー用Viewを直下に連結する実装（`BaseCardView` はMAIN領域の子を縦積みするだけでオーバーレイはできない）だったが、
  具体的な不具合内容は未確認。再挑戦する場合は原因調査から。
