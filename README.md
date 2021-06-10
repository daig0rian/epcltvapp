# EPGStation の録画を見る
"EPGStationの録画を見る"  はAndroid TV向けに設計されたEPGStation クライアントアプリです。

ブラウザを持たないAndroid TV から EPGStationの録画を見るために開発されました

## Google Play URL
https://play.google.com/store/apps/details?id=com.daigorian.epcltvapp

## 特徴
 - Android TV むけ標準UIのLeanbackテーマを使用
 - Android TVのリモコンだけで操作が完結
 - Android TVのリモコン内蔵マイクから録画済番組を声で検索
 - MX Player やVLCといった外部動画プレーヤーに対応することで高い再生性能を確保

## デモ
![](https://raw.githubusercontent.com/wiki/daig0rian/epcltvapp/images/demo.gif)

## 他のソリューションとの比較
### Kodi + plugin.video.epgstation
- （Google Play Storeの審査が通ったら）Google Play Sotreから一発で導入できてらくちん
- 番組を音声検索できる
- ルールごとに録画済一覧を見ることができる。


## 今後やりたい
 - 検索履歴を残して再検索を楽にする
 - 動画リストの一番右に「さらに読み込む」ボタンの追加

## 今後もきっとやらない
 - 録画予約関連UIの実装
   -  ソファーにLeanback（ふんぞり返り）ながらリモコンでやる作業ではないのではないか説
   -  ソファーでスマホからEPGStationの秀逸なUIを操作するのが現実的ではないのではないか説

## TODO
 - ~~審査用に著作権に問題のない動画をインポートしたEPGStation インスタンスの作成~~ 完了

## 現在の状況
 - GooglePlayStore Consoleからオープンテストで公開する手続き実施済（初めての経験でいまいちうまくいっているかわからない）
