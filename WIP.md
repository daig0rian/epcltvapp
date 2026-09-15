# WIP: 録画ルールの並び順（3択）・検索履歴の分離・遅延応答での落ち防止

## 目的

- 録画ルール一覧の並び順を「ルールの新しい順 / ルールの古い順 / 録画の新しい順」から選べるようにする
- 検索履歴の並び順は既存の真偽値キーのまま、履歴専用として残す
- 画面を離れた後に届いた API 応答で落ちないようにする
- 履歴に関わる操作で録画ルール全件の getRecorded() が走らないようにする

## 完了済み

- `RuleOrder` に3モード（`MODE_RULE_NEWEST` / `MODE_RULE_OLDEST` / `MODE_RECORDING_NEWEST`）、
  `modeFromPreference()` / `orderedRuleIds()` / `provisionalOrder()` を追加
- 設定は新キー `KEY_RULES_ORDER_MODE`（ListPreference、既定 `rule_newest`）。既存キーは履歴専用
- `RuleOrderCollector` に受け取った順と最新録画日時を保持させ、設定変更時は行を取り直さず並べ替え
- `refreshSearchHistoryRows()` を追加し、履歴の並び順・件数・クリア・検索からの復帰では履歴だけ作り直す
- **遅延応答での落ち防止**: `if (!isUiAlive) return`（`isUiAlive = isAdded`）を MainFragment の13箇所と
  初期化ラムダ、SearchFragment 6箇所、VideoDetailsFragment 2箇所に追加。
  トーストだけの箇所は `if (isAdded)` で囲んで、検索履歴の保存など副作用は残した
- 並び確定の診断ログを `Log.i` で追加（この端末では `Log.d` が出ないため）
- `gradlew clean :app:testDebugUnitTest :app:assembleDebug` が成功（66テスト green）
- 実機（Fire TV / モックサーバー）で並びを確認: ルールID 3 → 1 → 6 → 4 → 2 → 5

## 残タスク

- PlaybackVideoFragment に同型の穴が残っている（`getString` を `runOnUiThread` の外で呼ぶ箇所）
- 600件のルール読み込みが実用にならないほど遅い（実測で15分以上／100件未満）。順序の確定を
  `/api/recorded`（ruleId なし）数ページから作る方式へ変えると、最初から正しい順で挿入できる
- 実機でのプルダウンの見た目、レジューム位置や検索履歴が既存版と同じに動くかの確認

## 重要な決定事項

- 並べ替えのための API 呼び出しは増やさない。行の内容を作るために元から走っている
  getRecorded(ruleId) の応答を使い回す
- 1レスポンスごとに並べ替えない。全ルール分が揃った時点と、設定を変えた時点だけで並べ替える
- ロード中の仮の並びは「新しいルール順」。`ルールの古い順` を選んでいるときだけ受け取った順のまま
- 「録画の新しい順」で録画実績なし・取得失敗のルールは必ず末尾。同着は rule.id 昇順で安定させる
- 既存キーは真偽値のまま履歴専用にする（型を変えると既存利用者の保存値で ClassCastException になる）
- EPGStation の /api/rules は rule.id ASC、/api/recorded は isReverse=false で startAt DESC（サーバー実装で確認）
