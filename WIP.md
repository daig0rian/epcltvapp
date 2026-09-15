# WIP: 録画ルールの並び順（3択）と検索履歴の並び順

## 目的

- 録画ルール一覧の並び順を「ルールの新しい順 / ルールの古い順 / 録画の新しい順」から選べるようにする
- 検索履歴の並び順は既存の真偽値キーのまま、履歴専用として残す
- 履歴に関わる操作で録画ルール全件の getRecorded() が走らないようにする

## 完了済み

- `RuleOrder` に3モード（`MODE_RULE_NEWEST` / `MODE_RULE_OLDEST` / `MODE_RECORDING_NEWEST`）と `modeFromPreference()` / `orderedRuleIds()` を追加
- 設定は新キー `KEY_RULES_ORDER_MODE`（ListPreference、既定 `rule_newest`）。既存キー `KEY_RULES_ORDER_IS_NEWEST_FIRST` は真偽値のまま履歴専用にした
- `RuleOrderCollector` は受け取った順と最新録画日時を持ち続け、並び順の設定を変えても**行を取り直さずに並べ替える**（追加リクエスト0件）
- `refreshSearchHistoryRows()` を追加し、履歴の並び順・件数・クリア・検索からの復帰では履歴カテゴリだけを作り直す
- 設定画面で履歴の並び順トグルを「履歴表示設定」カテゴリへ移動。未使用だった `set_oldest_rule_first` を削除
- `gradlew clean :app:testDebugUnitTest :app:assembleDebug` が成功（全 65 テスト green）

## 残タスク

- 実機 (Android TV / Fire TV) での設定画面の見た目、プルダウンの操作、一覧の D-pad 操作の確認
- 600 件規模の実サーバーでの初期表示時間の計測

## 重要な決定事項

- 並べ替えのための API 呼び出しは増やさない。行の内容を作るために元から走っている getRecorded(ruleId) の応答を使い回す
- 1 レスポンスごとに並べ替えない。全ルール分が揃った時点と、設定を変えた時点だけで並べ替える
- 設定キーを分けたのは、既存キーが真偽値で履歴と共用されているため。型を変えると既存利用者の保存値で ClassCastException になる
- 「録画の新しい順」で録画実績なし・取得失敗のルールは必ず末尾。同着は EPGStation が返した rule.id 昇順で安定させる
- EPGStation の /api/rules は rule.id ASC、/api/recorded は isReverse=false で startAt DESC を返す（サーバー実装で確認済み）
