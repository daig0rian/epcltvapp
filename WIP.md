# WIP: 「録画を後から読み込み」を既定で ON にする (Issue #120)

## 目的

v1.42 のリリース前に、#117 で入った「録画を後から読み込み」(KEY_LAZY_LOAD) の既定を ON にする。
設定そのものをなくすのは今回の範囲外（Issue に「どこかのタイミングで」とある）。

## 決定事項

- 既定値は bool リソース `pref_val_lazy_load_default` にまとめ、`preferences.xml` の `defaultValue` と
  `MainFragment.isLazyLoad()` の両方がそれを読む
  - 新規インストール: 初回起動の `setDefaultValues()` が XML の既定値を書き込む
  - 前のバージョンから更新した端末: `setDefaultValues(..., false)` は2回目以降なにもしないので、
    設定画面を開くまで値が保存されず、`getBoolean` の既定値が使われる
- この設定は未リリースなので、値を保存している利用者はいない。更新すると EPGStation v2 の利用者は全員 ON になる
- 事前確認: AFTT（Fire OS 5）で ON / OFF を比較し、どちらも ANR なし・ON のほうが軽いことを確認した
  （Issue #120 のコメント）

## 完了

- [x] Issue #120 に AFTT での比較結果をコメント
- [ ] 既定値を ON にする（リソース・preferences.xml・MainFragment）
- [ ] MANUAL.md 1.2節（番組表からの録画）と 5.3節（録画を後から読み込み）を直す

## 残り

- ユーザーの動作確認（新規インストールで ON、更新で ON、OFF にしたものは OFF のまま）
- 確認後に WIP.md を消して PR
- その後 v1.42 のリリース準備をやり直す（ノートの下書きは scratchpad の v1.42-draft.md。
  「録画を後から読み込み」が既定 ON になったことを反映する必要がある）
