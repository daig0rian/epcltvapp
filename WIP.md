# WIP: 主音声を選んでいるのに副音声で再生が始まる不具合の修正

ブランチ: `fix/main-audio-track-selection`

## 目的

エンコード済み(MP4)の二ヶ国語録画の一部で、音声設定が「主音声」なのに副音声で再生が
始まる。これを直す。

## 原因(調査で確定済み)

`PlaybackVideoFragment.onTracksChanged` が、副音声を選んでいるときしかトラックを
明示指定していなかった。

```kotlin
if (hasSubAudio && preferSubAudio) {
    selectAudioTrack(1)
}
```

主音声のときは何もせず、ExoPlayer の `DefaultTrackSelector` の既定選択に委ねていた。
既定選択の比較順(`DefaultTrackSelector.AudioTrackInfo.compareTo`)は

  言語 → ロールフラグ → デフォルトフラグ → ロケール言語 → …
  → チャンネル数 → サンプリング周波数 → **ビットレート**

だが、二ヶ国語録画の主音声/副音声は上記のほぼ全項目が同値になる:

- 言語タグは両方 `und`(`mdhd.language`)
- `tkhd.flags` は両方 `0x7` → デフォルトフラグも同値
- 同じ AAC-LC / 48kHz / 2ch / 同じ `esds` avgBitrate

結果、**最後のビットレート比較だけで主/副が決まる**。ここで使われる `Format.bitrate` は
`peakBitrate != NO_VALUE ? peakBitrate : averageBitrate` (media3 `Format`) であり、MP4 では
`esds` の **maxBitrate(ピーク値)** が入る。これは主音声か副音声かとは無関係な値なので、
副音声側のピークがたまたま上回る録画では副音声が選ばれる。

同値のときは `Collections.max` が先勝ちで先頭トラックを残すため、大半の録画では
たまたま正しく主音声(先頭トラック)になっていた。「たまに起きる」のはこのため。

### 実測

サーバー上の二ヶ国語録画のうち音声2トラックを持つ15本について `esds` を実測し、
`DefaultTrackSelector` の比較を再現した結果 **3本が2番目のトラック(副音声)を選ぶ**。
報告された録画もその1本だった。差はごく小さい(0.008%〜0.3%)。

なお残り12本が正しく再生できていたことは、`audioGroups[0]` == 主音声 という
インデックスの前提そのものは正しいことの裏付けにもなっている。

## 変更内容

`onTracksChanged` で主音声側も必ず明示指定するようにした。

```kotlin
if (hasSubAudio) {
    selectAudioTrack(if (preferSubAudio) 1 else 0)
}
```

同じ `TrackSelectionParameters` の再設定は `ExoPlayerImpl.setTrackSelectionParameters` が
`parameters.equals(...)` で早期 return するため、`onTracksChanged` のたびに呼んでも
ループしない(確認済み)。

## 重要な決定事項

- 主/副の判定は引き続きトラックの並び順(`audioGroups[0]`=主, `[1]`=副)で行う。
  両トラックともメタデータが完全に同一(`und` / 同フラグ)で、並び順以外に手がかりが
  ないため。上記の実測でこの前提は裏付けられている。
- TS(tsreadex 経路)も同じ経路を通るため同時に直る。tsreadex は audio1/audio2 の順で
  必ず2ストリーム出すので並び順の前提は満たされる。

## 残タスク

- [ ] ユーザーによる実機動作確認
- [ ] 確認できたら WIP.md を削除してコミットし、PR 作成
