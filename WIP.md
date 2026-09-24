# WIP: サムネイル背景 ON でのクラッシュを直す

Issue #104 / ブランチ `fix/thumbnail-background-null-uri`

## 目的

録画カードを一度も選んでいない状態（接続設定なし・録画0件）で「サムネイルを背景に表示」を
ONにすると、`GlideUrl(null)` が例外を投げてアプリが落ちる。これを直す。

## 完了

- `MainFragment.updateBackground()` の先頭で、uri が null / 空なら起動直後と同じ背景色に戻して return

## 決定事項

- 対応案1（updateBackground 側で弾く）を採用。呼び出し元をすべて覆えるため
- `getThumbnailURL("")` が作るわざと無効な URL は空ではないので、従来どおり Glide の .error() へ落ちる
- 他の GlideUrl 呼び出し（OriginalCardPresenter / VideoDetailsFragment）は非 null の URL しか受けないため対象外

## 残タスク

- 実機確認
- WIP.md を削除してから PR 作成
