# スクリーンショット

README.md と MANUAL.md から参照している画像を置く場所。

## 撮り方

複数の端末が接続されていることが多いので、`-s` で対象を明示する。

```
# 接続と一覧確認
adb connect <端末のIP>:5555
adb devices -l

# 撮影して取り出す (<serial> は adb devices で出た端末名)
adb -s <serial> shell screencap -p /sdcard/shot.png
adb -s <serial> pull /sdcard/shot.png screenshots/main-screen.png
adb -s <serial> shell rm /sdcard/shot.png
```

1コマンドで済ませる場合。PowerShell の `>` はバイナリを壊すので `cmd /c` でくるむ。

```
cmd /c "adb -s <serial> exec-out screencap -p > screenshots/main-screen.png"
```

## 注意

**モックEPGStation**（`mock-epgstation/`）に接続した状態で撮影する。

```
cd mock-epgstation
node server.js
```

アプリの「設定 → 接続設定」に、起動時に表示されるIPアドレスとポートを入力してから撮影する。
