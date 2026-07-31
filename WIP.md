# WIP: TSシークバーへのサムネイル表示 (Phase 2)

## 目的

Phase 1（[#43](https://github.com/daig0rian/epcltvapp/pull/43)、マージ済み）でTS再生のシーク機能を実装した。そのシーク点の一部にサムネイルを付与し、ユーザーがより意味のある位置へシークできるようにする。

## 前提となる決定事項（Phase 1完了時点の議論より）

- サムネイルは**シーク点全部ではなく10点に1点程度**（`target = max(1, positions.size / 10)`）
- 選定順序は**二分木のBFS走査**（全体の中間点→前半/後半の中間点→…と疎から密に埋める）。中断されても常に「粗いが全体をカバーした状態」を保てる利点がある
- **最大の不確定要素**: 生のARIB放送TSのバイト位置から実際に1枚のフレームをデコードする手段が未検証。候補は`MediaMetadataRetriever`（Android標準、実装は楽だが生ARIB TSを正しく扱えるか不明）。もし使えなければ`tsreadex`経由でMediaCodecを使う自前実装が必要になり、コストが大きく上がる

## Phase 1の設計変更を踏まえた再整理

Phase 1は当初「N点を事前プローブしたテーブル」方式(v2)だったが、起動待ち時間の問題で「head/tailの2点のみ保持し、シーク位置は線形補間で概算、確定時に1回だけ補正プローブ」方式(v3)に変更された（[TsSeekDataProvider.kt](app/src/main/java/com/daigorian/epcltvapp/TsSeekDataProvider.kt)）。サムネイル選定順序(BFS)の設計はv2時代の「事前プローブ済みテーブル」を前提にしていたため、以下の点をv3に合わせて再整理する必要がある:

- `getSeekPositions()`の各点は**時刻のみ**で、バイト位置は`estimateByteOffset()`による概算のみ（未検証）。サムネイル生成には実際のバイト位置が必要なため、選定された点ごとに`TsProbe.refineSeekPoint()`相当の軽量プローブで補正するか、概算のまま使うかは要検討
  - サムネイルは元々「参考程度の見た目」なので概算のままでも実用上問題ない可能性がある。まず`MediaMetadataRetriever`自体が動くかどうかの検証を優先し、精度の話は後回しにする

## 残タスク

- [ ] **最優先**: `MediaMetadataRetriever`が生ARIB放送TS(HTTP経由、認証ヘッダ・Range対応込み)から実際にフレームを取得できるか、実機で検証する
- [ ] （検証がOKなら）サムネイル生成のタイミング・キャッシュ方針の設計
- [ ] BFS選定順序の実装（v3のgetSeekPositions()配列に対して）
- [ ] `TsSeekDataProvider.getThumbnail(index, callback)`の実装（現在は基底クラスのデフォルト実装＝何もしない）
- [ ] UI側の表示確認

## 重要な決定事項

- ビルドはAndroid Studioでユーザーが手動実行する（Claude Codeはgradleを叩かない）。`MediaMetadataRetriever`はAndroid実機/エミュレータでしか検証できないため、実機動作確認が必須。
- コルーチン未導入のプロジェクトのため、既存パターン（Handler + バックグラウンドThread）を踏襲する。
