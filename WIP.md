# WIP: 再生中に開いたコントロールが閉じない問題

ブランチ: `fix/playback-controls-auto-hide` (master から分岐)

## 目的

内蔵プレイヤーで再生中にコントロール(メニュー)を開くと開きっぱなしになる。
再生開始直後は3秒で閉じるのに、以下の操作で開いたときは閉じない:

- 再生中に Dpad の任意のボタンを押して開く
- 「最初から再生」を押す
- 最終話で「次のエピソード」を押して「次の番組はありません」を出す
- 「字幕ON」「字幕OFF」を押す

これらを「しばらく放置すると閉じる」に変える。

## 原因 (leanback-1.0.0 のソースで確認済み)

自動非表示は `PlaybackSupportFragment` のタイマー1本(`START_FADE_OUT`)だけで動く。起点は3つ。

| # | 起点 | タイマー長 |
|---|---|---|
| ① | `onResume()` | `playbackControlsAutoHideTimeout` (既定3000ms) |
| ② | `setControlsOverlayAutoHideEnabled(enabled)` ※**値が変化したときだけ**動作 | 同上 |
| ③ | `tickle()` (Dpad任意キーの ACTION_DOWN で必ず呼ばれる) | `playbackControlsAutoHideTickleTimeout` (**既定0＝タイマーを仕掛けない**) |

②の唯一の呼び出し元は `PlaybackTransportControlGlue.updatePlaybackState()` で、渡す値は `isPlaying`。
つまり Leanback は「**再生中だけ自動で閉じる／一時停止中は開いたままにする**」設計。

上記4件はすべて③が原因(再生状態が変わらないのでタイマーの起点がない)。
テーマ属性 `playbackControlsAutoHideTickleTimeout` を正の値にすれば Leanback の機能だけで直る。

## 決定事項

- **一時停止中に閉じないのは Leanback の設計意図なのでそのまま維持する。** (当初は変更予定だったが撤回)
- **エピソード一覧を見ている間は閉じない。** 一覧が閉じるとカーソルが今見ている回へ戻る仕様
  (`showControlsOverlay` の override) のため、タイトルを読んでいる間に閉じると選択位置を失う。
- 操作後の自動非表示までは **5秒**。Leanback 公式の推奨は「再生開始時の値(3秒)より長くする」。

## ③を有効にすると出る副作用 (個別実装で塞ぐ)

`tickle()` はシーク中・一覧閲覧中でも呼ばれるため、放置するとどちらも5秒で閉じてしまう。
シークについては `setSeekMode(true)` の `stopFadeTimer()` があるが、これはシーク開始の1回しか
効かないので、その後のキー操作で仕掛かるタイマーは止められない。

## 実装

1. `res/values/themes.xml` — `Theme.Epcltvapp` に `playbackControlsAutoHideTickleTimeout` (5000) を追加。
2. `PlaybackVideoFragment` — `setControlsOverlayAutoHideEnabled()` を override し、
   Leanback から渡された値を覚えた上で「シーク中」「一覧閲覧中」だけ落とすマスクにする。
   Leanback の判断(再生中かどうか)は打ち消さない。
3. シーク中の判定 — `setPlaybackSeekUiClient()` を override し、グルーが登録するクライアントを
   デコレータで包んで `onSeekStarted` / `onSeekFinished` を拾う。
4. 一覧閲覧中の判定 — `setOnItemViewSelectedListener` で選択中の行を見る (`ListRow` なら一覧)。

## 残タスク

- [ ] ユーザーによる実機での動作確認
- [ ] 確認後 WIP.md を削除してコミット → PR 作成

## 動作確認項目

変更対象:
- [ ] 再生中に Dpad で開く → 5秒で閉じる
- [ ] 「最初から再生」 → 閉じる (エンコード済み動画 / 録画オリジナルTS の両方)
- [ ] 最終話で「次のエピソード」→ トーストの後に閉じる
- [ ] 「字幕ON」「字幕OFF」 → 閉じる

回帰確認(変わらないこと):
- [ ] 再生開始直後 → 3秒で閉じる
- [ ] 一時停止 → 閉じない (Leanback の設計意図)
- [ ] シークを開始して確定しないまま放置 → 閉じない
- [ ] シーク確定 → 即座に閉じる / シーク中に戻るで取り消し
- [ ] エピソード一覧を見ている間 → 閉じない。コントロール行へ戻ると閉じる
- [ ] 次のエピソード・他のエピソードへの切り替え → 閉じる
- [ ] ライブ再生(録画ボタン・番組情報ボタン)
