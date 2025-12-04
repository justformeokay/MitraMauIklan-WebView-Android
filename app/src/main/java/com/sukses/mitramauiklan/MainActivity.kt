package com.sukses.mitramauiklan
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.sukses.mitramauiklan.ui.theme.MitraMauiklanTheme
import java.io.File

class MainActivity : ComponentActivity() {
	private val targetUrl = "https://mauiklan.com/id"
	private var filePathCallback: ValueCallback<Array<Uri>>? = null
	private var retryFileChooserIntent: Intent? = null
	private var webView: WebView? = null
	// URI untuk menyimpan foto kamera
	private var cameraImageUri: Uri? = null
	// Permission launcher
	private val permissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { isGranted: Boolean ->
		if (isGranted) {
			retryFileChooserIntent?.let {
				fileChooserLauncher.launch(it)
			}
		} else {
			filePathCallback?.onReceiveValue(null)
			filePathCallback = null
		}
		retryFileChooserIntent = null
	}
	// File chooser launcher
	private val fileChooserLauncher =
		registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			if (filePathCallback == null) return@registerForActivityResult
			var results: Array<Uri>? = null
			if (result.resultCode == Activity.RESULT_OK) {
				val data = result.data
				// Jika dari gallery / file manager
				if (data?.data != null) {
					results = arrayOf(data.data!!)
				}
				// Jika dari kamera (data == null)
				else if (cameraImageUri != null) {
					results = arrayOf(cameraImageUri!!)
				}
			}
			filePathCallback?.onReceiveValue(results)
			filePathCallback = null
			cameraImageUri = null
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		val onBackPressedCallback = object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				webView?.let {
					if (it.canGoBack()) {
						it.goBack()
					} else {
						isEnabled = false
						onBackPressedDispatcher.onBackPressed()
					}
				}
			}
		}
		onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
		setContent {
			MitraMauiklanTheme {
				MainWebviewScreen(
					targetUrl = targetUrl,
					mainActivity = this,
					fileChooserLauncher = fileChooserLauncher,
					onWebViewReady = { createdWebView ->
						this.webView = createdWebView
						onBackPressedCallback.isEnabled = true
					}
				)
			}
		}
	}

	/**
	 * File chooser + izin kamera
	 */
	fun launchFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>>) {
		this.filePathCallback = callback
		// Cek izin kamera
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
			!= PackageManager.PERMISSION_GRANTED
		) {
			retryFileChooserIntent = intent
			permissionLauncher.launch(Manifest.permission.CAMERA)
			return
		}
		// ==== Buat file untuk foto kamera ====
		val imageFile = File.createTempFile(
			"IMG_", ".jpg",
			cacheDir
		)
		cameraImageUri = FileProvider.getUriForFile(
			this,
			"${packageName}.fileprovider",
			imageFile
		)
		// Intent kamera
		val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
			putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraImageUri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		// Intent bawaan galeri
		val contentSelectionIntent = intent
		// Intent chooser gabungan
		val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
			putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
			putExtra(Intent.EXTRA_TITLE, "Pilih Sumber Upload")
			putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
		}

		// Jalankan chooser
		fileChooserLauncher.launch(chooserIntent)
	}
}

@Composable
fun MainWebviewScreen(
	targetUrl: String,
	mainActivity: MainActivity,
	fileChooserLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
	onWebViewReady: (WebView) -> Unit
) {
	var isRefreshing by remember { mutableStateOf(false) }
	AndroidView(
		modifier = Modifier.fillMaxSize(),
		factory = { context ->
			val webView = WebView(context).apply {
				settings.javaScriptEnabled = true
				settings.domStorageEnabled = true
				settings.allowFileAccess = true
				settings.allowContentAccess = true
				webViewClient = object : WebViewClient() {
					override fun shouldOverrideUrlLoading(
						view: WebView?,
						request: WebResourceRequest?
					): Boolean {
						val url = request?.url?.toString() ?: return false
						if (url.startsWith("whatsapp:") || url.contains("wa.me")) {
							try {
								val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
								context.startActivity(intent)
							} catch (_: Exception) { }
							return true
						}
						return false
					}
					override fun onPageFinished(view: WebView?, url: String?) {
						super.onPageFinished(view, url)
						isRefreshing = false
					}
				}
				webChromeClient = object : WebChromeClient() {
					override fun onShowFileChooser(
						webView: WebView?,
						filePathCallback: ValueCallback<Array<Uri>>?,
						fileChooserParams: FileChooserParams?
					): Boolean {
						if (filePathCallback == null || fileChooserParams == null) return false
						fileChooserParams.createIntent()?.let { intent ->
							mainActivity.launchFileChooser(intent, filePathCallback)
						}
						return true
					}
				}
			}
			onWebViewReady(webView)
			webView.loadUrl(targetUrl)
			val swipeRefresh = SwipeRefreshLayout(context).apply {
				addView(webView)
				setOnRefreshListener {
					isRefreshing = true
					webView.reload()
				}
			}
			return@AndroidView swipeRefresh
		},
		update = {
			it.isRefreshing = isRefreshing
		}
	)
}
