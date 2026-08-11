# WIP: Fire TV でマイクオーブを表示しない (Issue #53)

ブランチ: `fix/hide-mic-orb-on-firetv`

## 目的

Fire TV の検索画面に表示されるマイクのオーブ（音声検索アイコン）を非表示にする。
あわせて検索欄のプレースホルダーを「番組名を音声検索」ではなく「番組名を検索」にする。

Fire OS には Google の音声認識サービスが無く、アプリ内音声検索は使えない。
現状はコールバックを空実装で潰しているだけなので「押せるが反応しないオーブ」が残っている。

## 調査でわかった Leanback の内部挙動 (leanback 1.0.0)

- `SearchBar.isVoiceMode()` は `mSpeechOrbView.isFocused()` を返すだけ。
  → **オーブを GONE にすればフォーカスが当たらなくなり、プレースホルダーも自動的に
  「番組名を検索」に固定される。** 文言のためだけの追加対応は不要。
- `lb_search_bar.xml` では検索欄が `layout_toEndOf="@id/lb_search_bar_speech_orb"`。
  オーブを GONE にすると RelativeLayout がこのルールを無視するため、検索欄が
  左に 108dp ほど寄る（marginStart 70dp の位置になる）。**要目視確認。**
- `SearchSupportFragment` は画面表示の 300ms 後に `SearchBar.startRecognition()` を
  自動実行する。ここで `mSpeechRecognitionCallback != null` だと
  - プレースホルダーが空文字にされる
  - `mRecognizing = true` のまま固定される → **入力文字が `mSearchQuery` に
    反映されず、検索が実行できなくなる**（従来はオーブからフォーカスが外れた際の
    `stopRecognition()` で自然に解除されていた。オーブを消すとこの経路が消える）
  → 空コールバックをやめ、代わりに `SearchBar.setSpeechRecognizer(null)` を
  onResume で呼ぶ方式に変更した。認識器が無いと `startRecognition()` は
  フォーカス移動だけして即 return するため、従来のフォーカス挙動は維持される。

## 完了済み

- [x] Issue #53 の調査、Leanback 1.0.0 のソース確認
- [x] `SearchFragment.kt` の修正（オーブ非表示・認識器の無効化・TODO 削除）
- [x] `MANUAL.md` の記述更新

## 残タスク

- [ ] ユーザーによる Fire TV 実機での動作確認
- [ ] Android TV 実機でのリグレッション確認（音声検索が従来どおり動くこと）
- [ ] 確認が取れたら WIP.md を削除してコミット → PR 作成

## 動作確認してほしいこと

### Fire TV

1. 検索画面にマイクのオーブが表示されないこと
2. プレースホルダーが「番組名を検索」になっていること（「音声検索」が出ない）
3. 検索欄の位置が不自然でないこと（左に寄る。見た目が悪ければ marginStart を調整する）
4. 文字入力 → 決定 で検索が実行できること（**最重要**。ここが壊れると検索不能）
5. 検索履歴が復元された状態で画面を開いても操作できること
6. 画面を開いた直後にソフトキーボードが出る挙動が許容できるか
   （従来はオーブにフォーカスが当たっていたのでキーボードは出なかった）

### Android TV / Google TV

7. マイクのオーブが従来どおり表示され、音声検索が動くこと
8. プレースホルダーが「番組名を音声検索」→「番組名を検索」と従来どおり切り替わること
