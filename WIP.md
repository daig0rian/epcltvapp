# WIP: 録画ルール一覧を「最近録画された順」に並べる

## 目的

録画ルールの行を、そのルールによって最後に録画された番組の startAt が新しい順に並べる。600 件規模のルールがあっても、並べ替えのための追加 API 呼び出しを出さず、一覧の再配置も 1 回で済ませる。

## 完了済み

- ルール行の並び順ロジックを RuleOrder に切り出し、単体テスト RuleOrderTest を追加した
- MainFragment に RuleOrderCollector を追加し、既存の getRecorded(ruleId) の応答から latestRecordedAt を集めるようにした（追加リクエスト 0 件）
- 全ルール分が揃ってから RuleOrder.sortByLatestRecorded() と reorderCategory() を 1 回だけ実行する
- 並びが変わるときだけ再配置し、選択中の行は headerId で追従させる
- gradlew clean :app:testDebugUnitTest :app:assembleDebug が成功（全 60 テスト green）

## 残タスク

- 実機 (Android TV / Fire TV) での D-pad 操作と表示順の確認
- 600 件規模の実サーバーでの初期表示時間の計測

## 重要な決定事項

- 並べ替えのための API 呼び出しは増やさない。行の内容を作るために元から走っている getRecorded(ruleId) の応答をそのまま使う
- 1 レスポンスごとに並べ替えない。全ルール分が揃った時点で 1 回だけ確定する（600 件で一覧全体の再配置が繰り返し走るのを避けるため）
- 録画実績なし・取得失敗のルールは末尾。昇順/降順の影響で上に来ないよう Long.MIN_VALUE をキーにしている。同一日時は EPGStation が返したルール順で安定させる
- 表示設定 KEY_RULES_ORDER_IS_NEWEST_FIRST は履歴の並びにはそのまま効かせる。ルールについては「最近録画された順」が第一キーになり、この設定は同着時の tie-breaker として残る
- EPGStation v1 / v2 のどちらも startAt を持つため、両方で並べ替えが効く
