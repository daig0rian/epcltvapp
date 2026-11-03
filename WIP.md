# WIP: 内蔵プレイヤーのタイトル上端が切れる (Issue #123)

## 目的

再生画面の操作パネルで、タイトル（大きい文字）の漢字・かなの上端が切れるのを直す。

## 原因（Issue #123 に詳細）

- Leanback の `AbstractDetailsDescriptionPresenter` はタイトルの上マージンを `26dp + ascent(Roboto 34sp)` とし、
  負の値（density 2.0 で -11px）になる。TextView が説明欄の上端からはみ出す
- 再生画面のレイアウトでは説明欄の親（`description_dock` / `controls_card`）が子を切り抜くため、はみ出た分が消える
- Android 9 以降で日本語の代替フォントの ascent が Roboto より大きい機種（AOSP 標準の NotoSansCJK.ttc）では、
  行が広がってベースラインが下がるので切れない。BRAVIA・AFTT は切れる、Streamer は切れない
- Leanback 1.2.0 / 1.3.0-alpha02 でも未修正

## 方針（ユーザー合意済み: 案1）

`MyPlaybackTransportControlGlue.onCreateRowPresenter()` を上書きし、説明欄のプレゼンターを差し替える。
はみ出す分（`-(26dp + ascent)`）を説明欄の上パディングにする。再生画面だけに効かせる。
詳細画面は切れていないので触らない。

## 完了

- [x] ブランチ作成
- [x] 実装（`PlaybackDescriptionPresenter` を追加し、glue の `onCreateRowPresenter()` で差し替え）

## 残タスク

- [ ] 3機種（BRAVIA・AFTT・Streamer）で動作確認（ユーザー）
- [ ] PR

## 別件

- Leanback 1.2.0 への更新は Issue #124
