# WIP: リモコンの専用キー（字幕・音声切換・早戻し/早送り・前/次・停止）に対応する

## 目的

BRAVIA 付属リモコンの下段にある専用キーを、内蔵プレーヤーの再生画面で使えるようにする。

| ボタン | キーコード | 動作 |
|---|---|---|
| 字幕 | `CAPTIONS` | 字幕の ON/OFF（画面の CC ボタンと同じ） |
| 音声切換 | `MEDIA_AUDIO_TRACK` | 主音声/副音声（画面の音声ボタンと同じ） |
| 早戻し | `MEDIA_REWIND` | 10秒戻る |
| 早送り | `MEDIA_FAST_FORWARD` | 30秒進む |
| 前 | `MEDIA_PREVIOUS` | 最初から再生（画面のボタンと同じ） |
| 次 | `MEDIA_NEXT` | 次のエピソード（画面のボタンと同じ） |
| 停止 | `MEDIA_STOP` | 再生画面を閉じる |
| 再生 / 一時停止 | `MEDIA_PLAY` / `MEDIA_PLAY_PAUSE` | 変更なし（Leanback が元から処理している） |

## 完了済み

- BRAVIA (BRAVIA_4K_UR3 / Android 10) で、各ボタンがどのキーコードで届くかを adb で調査した
  - 全ボタンが Bluetooth 経由。字幕〜停止の9つはシステムに横取りされず前面アプリへ届く
  - 「ヘルプ」「録画リスト」はソニーのアプリに横取りされるため、アプリからは対応できない
  - 「一時停止」は `MEDIA_PAUSE` ではなく `MEDIA_PLAY_PAUSE`（トグル）で届く
- 実装
  - `PlaybackActivity.dispatchKeyEvent` → `PlaybackVideoFragment.onRemoteKey` でキーを受ける
  - 早戻し/早送りの飛び先の計算を `PlaybackSkip` に切り出し、単体テスト `PlaybackSkipTest` を追加
  - 録画TSの疑似シークに通し番号を入れ、後から来たシークが勝つようにした

## 残タスク

- ビルドと単体テスト（ユーザーが Android Studio で実行）
- 実機確認（下の「確認項目」）
- MANUAL.md にリモコンのキーの説明を足す（動作確認が済んでから）

## 確認項目

BRAVIA のリモコンが使えないときは、どの端末でも `adb shell input keyevent <コード>` で同じキーを送れる。
`CAPTIONS`=175 / `MEDIA_AUDIO_TRACK`=222 / `MEDIA_REWIND`=89 / `MEDIA_FAST_FORWARD`=90 /
`MEDIA_PREVIOUS`=88 / `MEDIA_NEXT`=87 / `MEDIA_STOP`=86

- 録画TS・エンコード済み動画のそれぞれで、早戻し/早送りが 10秒/30秒 動く
- 録画TSで早送りを3回続けて押すと 90秒進む（途中の着地点で止まらない）
- 終わりの近くで早送りを押しても後ろへ戻らない
- 字幕・音声切換でコントロールが開かず、トーストだけが出る。コントロールを開くとボタンの表示も切り替わっている
- 前=最初から、次=次のエピソード、停止=詳細画面へ戻る
- シークバーで位置を選んでいる最中の早戻し/早送りは、これまでどおり目盛りが動く
- ライブ視聴では早戻し/早送り・前/次が何もしない
- BRAVIA の実機リモコンで、logcat の `onRemoteKey` に想定どおりのキーコードが出る

## 重要な決定事項

- **Leanback のキー処理を通さず、Activity の dispatchKeyEvent で受ける。** Leanback は消費したキーを
  「コントロールを開く合図」として扱う（`PlaybackSupportFragment.onInterceptInputEvent` → `tickle()`）ため、
  グルーの `onKey` で受けると、字幕を切り替えただけで映像の下側がコントロールに覆われる
- **押しっぱなしの繰り返しでは動かさない。** この端末のキーリピートは 50ms 間隔で、そのまま通すと
  1秒押しただけで10分進む。録画TSでは1回ごとに MediaSource を開き直すので負荷も大きい
- **シークポイントを選んでいる最中の早戻し/早送りは Leanback に任せる。** 選んでいる途中で意味を変えない
- **飛べる範囲はシークバーと同じ。** 録画TSは終わりの15秒以内へ飛べない（`TsSeekDataProvider.maxSeekableMs`）。
  上限を超える早送りは上限で止め、すでに上限より先に居るなら何もしない（クランプで後ろへ戻るのを防ぐ）
- 字幕・音声は画面のボタンと同じ経路（グルーの `onActionClicked`）を通し、ボタンの表示を揃える
- 前/次は画面にボタンが出ている再生（録画の再生）でだけ効かせる
