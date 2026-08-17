# WIP: 文字スーパー(SI)ボタンの削除

ブランチ: `chore/remove-superimpose-toggle`

## 目的

再生画面の文字スーパー(SI)ON/OFFボタンを削除し、libaribcaption を使っている
(=ネイティブTS処理ON の生TS再生) ときは文字スーパーを常に表示する。

理由: 地震・台風のデータで確認したところ、ON/OFF で差がなく、災害情報などは
どちらでも表示された。実質、文字スーパーとしてデータが送られてくることがほぼない。
災害情報・地震情報は局側で映像に焼き込んで送られてきているとみられる。

## 完了済み

- `PlaybackVideoFragment.kt`
  - `superimposeEnabled` / `PREF_SUPERIMPOSE_ENABLED` / `toggleSuperimpose()` を削除
  - `MyPlaybackTransportControlGlue` から `superimposeAction`・`hasSuperimpose` 引数・
    `superimposeEnabled` 引数・`ACTION_ID_SUPERIMPOSE`・クリック処理を削除
  - 文字スーパーのデコード/表示経路は残し、常に有効(`enabled = true`)にした
  - ボタン列に関するコメント(CC/SI/音声 → CC/音声、最大構成7個 → 6個)を更新
- 文字列リソース `superimpose_on/off`・`action_superimpose_on/off` を en/ja から削除
- アイコン `ic_action_superimpose_on.xml` / `ic_action_superimpose_off.xml` を削除
- `MANUAL.md` の該当記述を更新(ボタン表・状態記憶の表・ネイティブTS処理の説明)

## 残タスク

- ユーザーによる実機での動作確認
  - 生TS再生(ネイティブTS処理ON)で SI ボタンが消えていること
  - CC・音声ボタンの並びが崩れていないこと
  - 文字スーパーが送られてくる場面があれば表示されること
- 確認後 WIP.md を削除してコミット → PR 作成
