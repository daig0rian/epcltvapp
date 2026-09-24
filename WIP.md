# WIP: 復帰のたびに検索履歴の行が作り直される

Issue #102 / ブランチ `fix/search-history-row-rebuild`

## 目的

メイン画面へ戻るたびに検索履歴の行が消えて作り直され、選択位置も戻らない。これを直す。

## 完了

- `refreshSearchHistoryRows()`: 並んでいる履歴（キーワードと並び順）が前回と同じなら行を消さず、
  既存行を使い回して中身だけ取り直す。変わったときだけ `deleteCategory` して作り直す
- `MainMenuAdapter.listRowsInCategory()` を追加（比較用）
- `updateRows()` からの呼び出しでも `selectedRowHeaderId()` / `restoreSelection()` で挟む（他の4箇所と揃える）

## 決定事項

- 比較はフィールドに控えず、アダプタに実際に並んでいる行（headerId と見出し）で行う。
  reloadContentRows などで行が消えても控えとずれないため
- 既存行を使い回すと見出しは書き換わらない（headerId がカテゴリ内の位置で決まる）ので、
  一覧が変わったときは従来どおり作り直す
- 中身の取り直し（履歴件数ぶんの getRecorded）は残す。戻ったときに新しい録画を反映するため

## 残タスク

- 実機確認
- WIP.md を削除してから PR 作成
