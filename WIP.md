# WIP: ルールIDを持たない録画で「同じルールの最新の録画」を出さない

ブランチ: `fix/hide-same-rule-row-without-rule-id`

## 目的

番組表やライブ再生から個別に録画した番組の詳細画面で、「同じルールの最新の録画」の行に
全ルールの録画（＝「最近の録画」と同じ内容）が並んでしまう問題を直す。

## 原因

個別録画した番組は `ruleId` を持たない (`null`)。
[VideoDetailsFragment.kt:458](app/src/main/java/com/daigorian/epcltvapp/VideoDetailsFragment.kt#L458)
はその値をそのまま `GetRecordedParam(rule = ...)` / `GetRecordedParamV2(ruleId = ...)` に渡していた。
Retrofit は値が null の `@Query` をリクエストから省くため、ルールでの絞り込みが一切効かない
`getRecorded` 呼び出しになり、全録画が返ってきていた。

※ユーザーの推測は「ルールIDが0」だったが、0 なら `ruleId=0` が送信されて結果は空になるはず。
「全ルールの録画が出る」症状はパラメータ自体が省かれたときのもの。念のため 0 も弾いている。

## 完了済み

- `updateRelatedMovieListRow()` で `ruleId` が null または 0 のときは
  「同じルールの最新の録画」の行自体を追加しないようにした。
  行の追加は `updateContentsListRow()` の中で行われるため、呼ばなければ行は生成されない。
- MainFragment 側は確認済みで対応不要（ルール一覧から得た `rule.id` を渡しており必ず有効な値）。

## 残タスク

- ユーザーによる実機動作確認
  - 番組表/ライブ再生から個別に録画した番組の詳細画面 → 「同じルールの最新の録画」の行が出ないこと
  - ルールで録画された番組の詳細画面 → 従来どおり同じルールの録画一覧が出ること
- 確認が取れたら WIP.md を削除してコミット → PR 作成
