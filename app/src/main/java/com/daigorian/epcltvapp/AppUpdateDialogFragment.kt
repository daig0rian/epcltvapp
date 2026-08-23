package com.daigorian.epcltvapp

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.daigorian.epcltvapp.githubcaller.GitHubRelease
import com.daigorian.epcltvapp.githubcaller.GitHubReleaseApi
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

/**
 * 設定行の「アップデートを確認」カードから開く、更新の一連の流れを収めたダイアログ。
 *
 * この機能は次の2つの原則で設計してある。実装を変えるときはここを崩さないこと。
 *
 * **1. 利用者が明示的に操作しない限り外部へ通信しない。**
 * このアプリの利用者層は自宅で録画サーバーを運用している層で、意図しない外部通信を嫌う。
 * よって起動時チェックも定期確認も行わない。GitHub へ出ていくのは、カードを押した上で
 * [Step.CONSENT] の同意画面で「接続して確認」を選んだときだけ。
 * **どの段でも初期フォーカスは通信もインストールもしない側のボタンに置く**
 * (リモコンで決定を連打しても外へ出ていかないようにするため)。
 *
 * **2. 状態を持たない。** 確認結果を端末に保存してカードの見た目を変える、といったことはしない。
 * そういう作りのアプリは一般的でなく、利用者が無意識に持つモデルと合わないため。
 * 閉じれば何も残らず、次に開いたときはまた [Step.CONSENT] から始まる
 * (ダウンロード済みAPKはキャッシュに残るが、次のダウンロード開始時に消される)。
 */
class AppUpdateDialogFragment : DialogFragment() {

    /** ダイアログが表す段。1枚のレイアウトを差し替えて全部の段を表す。 */
    private enum class Step { CONSENT, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, PERMISSION, ERROR }

    private var step = Step.CONSENT
    private var release: GitHubRelease? = null
    private var downloadedApk: File? = null
    private var downloadPercent = 0
    private var errorMessage: String = ""

    /** [Step.ERROR] の「再試行」で何をやり直すか。やり直しようがないエラーでは null。 */
    private var retryAction: (() -> Unit)? = null

    /** 「不明なアプリのインストール」の設定画面が無い機種か。無ければ手順を文章で案内する。 */
    private var permissionScreenUnavailable = false

    private var checkCall: Call<GitHubRelease>? = null
    private var downloader: AppUpdateDownloader? = null

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var negativeButton: Button
    private lateinit var positiveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ProgramInfoDialogTheme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_app_update, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleView = view.findViewById(R.id.app_update_title)
        bodyView = view.findViewById(R.id.app_update_body)
        scrollView = view.findViewById(R.id.app_update_scroll)
        progressBar = view.findViewById(R.id.app_update_progress)
        negativeButton = view.findViewById(R.id.app_update_negative)
        positiveButton = view.findViewById(R.id.app_update_positive)

        // リリースノートが長いときのために本文をDpadでスクロールできるようにする
        // (ProgramInfoDialogFragment と同じ作法)。
        scrollView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val scrollAmount = bodyView.lineHeight * 3
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (scrollView.canScrollVertically(1)) {
                            scrollView.smoothScrollBy(0, scrollAmount); true
                        } else false
                    KeyEvent.KEYCODE_DPAD_UP ->
                        if (scrollView.canScrollVertically(-1)) {
                            scrollView.smoothScrollBy(0, -scrollAmount); true
                        } else false
                    else -> false
                }
            } else false
        }

        render()
    }

    override fun onStart() {
        super.onStart()
        // 幅は固定、高さは中身なり。同意画面のような短い本文で無駄に大きくならないようにする
        // (リリースノートが長いときは画面の高さまで伸びる)。
        val width = (resources.displayMetrics.widthPixels * 0.7).toInt()
        dialog?.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onResume() {
        super.onResume()
        // 「不明なアプリのインストール」の設定画面から戻ってきたところ。許可されていれば続きを進める。
        if (step == Step.PERMISSION && AppUpdateDownloader.canInstall(requireContext())) {
            downloadedApk?.let { install(it) }
        }
    }

    override fun onDestroyView() {
        // 進行中の通信を必ず止める。閉じたのに裏で通信が続くのは、この機能の原則に反する。
        checkCall?.cancel()
        checkCall = null
        downloader?.cancel()
        downloader = null
        super.onDestroyView()
    }

    // --- 段の遷移 ---

    private fun goTo(next: Step) {
        step = next
        if (view != null) render()
    }

    private fun render() {
        progressBar.visibility = if (step == Step.DOWNLOADING) View.VISIBLE else View.GONE

        when (step) {
            Step.CONSENT -> {
                titleView.setText(R.string.app_update_title)
                bodyView.setText(R.string.app_update_consent_body)
                setNegative(R.string.cancel) { dismissAllowingStateLoss() }
                setPositive(R.string.app_update_consent_ok) { startCheck() }
            }
            Step.CHECKING -> {
                titleView.setText(R.string.app_update_title)
                bodyView.setText(R.string.app_update_checking)
                setNegative(R.string.cancel) { dismissAllowingStateLoss() }
                hidePositive()
            }
            Step.UP_TO_DATE -> {
                titleView.setText(R.string.app_update_title)
                bodyView.text = getString(R.string.app_update_up_to_date, installedVersionName().orEmpty())
                setNegative(R.string.close) { dismissAllowingStateLoss() }
                hidePositive()
            }
            Step.AVAILABLE -> {
                titleView.text = getString(R.string.app_update_available_title, AppVersion.display(release?.tagName))
                bodyView.text = availableBody()
                setNegative(R.string.app_update_later) { dismissAllowingStateLoss() }
                // 一度落としてあれば再ダウンロードしない(インストールを取り消して戻ってきた場合)。
                val alreadyDownloaded = downloadedApk?.exists() == true
                setPositive(
                    if (alreadyDownloaded) R.string.app_update_install else R.string.app_update_do_update
                ) { startDownloadOrInstall() }
            }
            Step.DOWNLOADING -> {
                titleView.text = getString(R.string.app_update_available_title, AppVersion.display(release?.tagName))
                showProgress(downloadPercent)
                setNegative(R.string.app_update_abort) { abortDownload() }
                hidePositive()
            }
            Step.PERMISSION -> {
                titleView.setText(R.string.app_update_title)
                setNegative(R.string.close) { dismissAllowingStateLoss() }
                if (permissionScreenUnavailable) {
                    bodyView.setText(R.string.app_update_permission_manual)
                    hidePositive()
                } else {
                    bodyView.text = getString(R.string.app_update_permission_body, getString(R.string.app_name))
                    setPositive(R.string.app_update_permission_open) { openUnknownSourcesSettings() }
                }
            }
            Step.ERROR -> {
                titleView.setText(R.string.app_update_title)
                bodyView.text = errorMessage
                setNegative(R.string.close) { dismissAllowingStateLoss() }
                val retry = retryAction
                if (retry == null) hidePositive() else setPositive(R.string.app_update_retry) { retry() }
            }
        }

        scrollView.scrollTo(0, 0)
        // 初期フォーカスは常に「何もしない側」。決定の連打で通信やインストールが始まらないようにする。
        negativeButton.requestFocus()
    }

    private fun setNegative(textResId: Int, onClick: () -> Unit) {
        negativeButton.setText(textResId)
        negativeButton.setOnClickListener { onClick() }
    }

    private fun setPositive(textResId: Int, onClick: () -> Unit) {
        positiveButton.visibility = View.VISIBLE
        positiveButton.setText(textResId)
        positiveButton.setOnClickListener { onClick() }
    }

    private fun hidePositive() {
        positiveButton.visibility = View.GONE
        positiveButton.setOnClickListener(null)
    }

    private fun showProgress(percent: Int) {
        progressBar.progress = percent
        bodyView.text = getString(R.string.app_update_downloading, percent)
    }

    private fun showError(messageResId: Int, retry: (() -> Unit)?) {
        errorMessage = getString(messageResId)
        retryAction = retry
        goTo(Step.ERROR)
    }

    // --- 確認 ---

    /** ここが唯一の外部への出口。利用者が同意画面で承諾したときにだけ呼ばれる。 */
    private fun startCheck() {
        goTo(Step.CHECKING)
        val call = GitHubReleaseApi.getLatestRelease()
        checkCall = call
        call.enqueue(object : Callback<GitHubRelease> {
            override fun onResponse(call: Call<GitHubRelease>, response: Response<GitHubRelease>) {
                if (!isAdded) return
                val latest = response.body()
                if (!response.isSuccessful || latest == null) {
                    showError(R.string.app_update_error_no_release, retry = { startCheck() })
                    return
                }
                release = latest
                goTo(
                    if (AppVersion.isNewer(latest.tagName, installedVersionName())) Step.AVAILABLE
                    else Step.UP_TO_DATE
                )
            }

            override fun onFailure(call: Call<GitHubRelease>, t: Throwable) {
                if (!isAdded || call.isCanceled) return
                showError(R.string.app_update_error_network, retry = { startCheck() })
            }
        })
    }

    // --- ダウンロードとインストール ---

    private fun startDownloadOrInstall() {
        val existing = downloadedApk
        if (existing != null && existing.exists()) {
            install(existing)
            return
        }

        val asset = AppVersion.pickApkAsset(release?.assets)
        val url = asset?.browserDownloadUrl
        if (asset == null || url == null) {
            showError(R.string.app_update_error_no_apk, retry = null)
            return
        }

        downloadPercent = 0
        goTo(Step.DOWNLOADING)
        downloader = AppUpdateDownloader(requireContext()).apply {
            start(url, asset.size, object : AppUpdateDownloader.Listener {
                override fun onProgress(percent: Int) {
                    if (!isAdded || step != Step.DOWNLOADING) return
                    downloadPercent = percent
                    showProgress(percent)
                }

                override fun onCompleted(file: File) {
                    if (!isAdded) return
                    downloadedApk = file
                    install(file)
                }

                override fun onFailed(messageResId: Int) {
                    if (!isAdded) return
                    showError(messageResId, retry = { startDownloadOrInstall() })
                }
            })
        }
    }

    private fun abortDownload() {
        downloader?.cancel()
        downloader = null
        downloadedApk = null
        goTo(Step.AVAILABLE)
    }

    private fun install(apk: File) {
        if (!AppUpdateDownloader.canInstall(requireContext())) {
            goTo(Step.PERMISSION)
            return
        }
        try {
            startActivity(AppUpdateDownloader.installIntent(requireContext(), apk))
            // インストーラーが前面に出る。成功すればこのアプリは終了させられる。
            // 利用者が取り消して戻ってきたときのために、落とし済みAPKを使う状態に戻しておく。
            goTo(Step.AVAILABLE)
        } catch (e: ActivityNotFoundException) {
            showError(R.string.app_update_error_install, retry = null)
        }
    }

    private fun openUnknownSourcesSettings() {
        try {
            startActivity(AppUpdateDownloader.unknownSourcesSettingsIntent(requireContext()))
        } catch (e: ActivityNotFoundException) {
            // TV機種によってはこの設定画面が無い。resolveActivity は Android 11 以降の
            // パッケージ可視性で当てにならないので、実際に投げて捕まえた結果で判断する。
            permissionScreenUnavailable = true
            render()
        }
    }

    // --- その他 ---

    private fun installedVersionName(): String? = try {
        val ctx = requireContext()
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun availableBody(): String {
        val notes = release?.body?.trim().orEmpty().ifEmpty { getString(R.string.app_update_no_release_notes) }
        val installed = getString(R.string.app_update_installed_is, installedVersionName().orEmpty())
        return "$notes\n\n$installed"
    }

    companion object {
        const val TAG = "AppUpdateDialog"

        fun newInstance(): AppUpdateDialogFragment = AppUpdateDialogFragment()
    }
}
