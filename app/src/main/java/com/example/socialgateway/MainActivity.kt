package com.example.socialgateway

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.MediaRecorder
import android.os.AsyncTask
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL
import java.util.*
import kotlin.math.roundToInt

fun log(message: String) {
    Log.d("SocialGateway", message)
}

data class SocialApp(
    val name: String,
    val packageName: String,
    val imageId: Int)

val socialApps = listOf(
    SocialApp("Telegram", "org.telegram.messenger", R.drawable.telegram),
    SocialApp("WhatsApp", "com.whatsapp", R.drawable.whatsapp),
    SocialApp("Facebook", "com.facebook.katana", R.drawable.facebook),
    SocialApp("Facebook Messenger", "com.facebook.orca", R.drawable.facebook_messenger),
    SocialApp("Instagram", "com.instagram.android", R.drawable.instagram),
    SocialApp("Signal", "org.thoughtcrime.securesms", R.drawable.signal),
    SocialApp("Snapchat", "com.snapchat.android", R.drawable.snapchat))

fun openConnection(route: String): HttpURLConnection {
    return URL("http://192.168.178.30:5000$route").openConnection() as HttpURLConnection
}

fun postToServer(data: ByteArray, route: String) {
    AsyncTask.execute {
        val answerConnection = openConnection(route)
        try {
            answerConnection.requestMethod = "POST"
            answerConnection.doOutput = true
            answerConnection.outputStream.write(data)
            if (answerConnection.responseCode != HTTP_OK) {
                throw ConnectException("response code ${answerConnection.responseCode}")
            }
        } catch (exception: ConnectException) {
            log("could not send answer: ${exception.message.orEmpty()}")
        } finally {
            answerConnection.disconnect()
        }
    }
}

fun dp(value: Float, resources: Resources): Int {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()
}

fun initSocialAppInGrid(socialAppsGrid: GridLayout, context: Context, socialApp: SocialApp, onClickListener: () -> Unit) {
    socialAppsGrid.addView(ImageView(context).apply {
        layoutParams = GridLayout.LayoutParams().apply {
            width = dp(90f, resources)
            height = dp(90f, resources)
            rightMargin = dp(20f, resources)
            bottomMargin = dp(20f, resources)
        }

        setImageResource(socialApp.imageId)

        setOnClickListener { onClickListener() }
    })
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.social_apps_grid)

        val socialAppsGrid = findViewById<GridLayout>(R.id.social_apps_grid)
        socialApps.forEach { socialApp ->
            initSocialAppInGrid(socialAppsGrid, this, socialApp) {
                startActivity(Intent(this, QuestionBeforeLaunchActivity::class.java).apply {
                    putExtra("socialAppName", socialApp.name)
                    putExtra("socialAppPackageName", socialApp.packageName)
                })
            }
        }
    }
}

class QuestionBeforeLaunchActivity : AppCompatActivity() {

    private lateinit var userId: String

    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO check userId is always the same
        userId = getPreferences(Context.MODE_PRIVATE).getString("userId", "").orEmpty().ifEmpty {
            UUID.randomUUID().toString()
        }

        val socialAppName = intent?.extras?.getString("socialAppName").orEmpty()

        if (socialAppName.isEmpty()) {
            log("socialAppName must not be empty")
            return
        }

        val socialAppPackageName = intent?.extras?.getString("socialAppPackageName").orEmpty()
        if (socialAppPackageName.isEmpty()) {
            log("socialAppPackageName must not be empty")
            return
        }

        val socialAppIntent = packageManager.getLaunchIntentForPackage(socialAppPackageName)
        if (socialAppIntent == null) {
            val message = resources.getString(R.string.X_was_not_found_on_your_device, socialAppName)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        AsyncTask.execute {
            val question: String
            val questionConnection = openConnection("/question?app_name=$socialAppName")
            try {
                if (questionConnection.responseCode != HTTP_OK) {
                    throw ConnectException("response code ${questionConnection.responseCode}")
                }

                question = questionConnection.inputStream.reader().readText()
            } catch (exception: ConnectException) {
                runOnUiThread {
                    Toast.makeText(this, "server unreachable, starting app...", Toast.LENGTH_SHORT).show()
                }
                startActivity(socialAppIntent)
                log("could not request question: ${exception.message.orEmpty()}")
                finish()
                return@execute
            } finally {
                questionConnection.disconnect()
            }

            runOnUiThread {
                val linearLayout = layoutInflater.inflate(R.layout.answer_dialog, null)
                val answerEditText = linearLayout.findViewById<EditText>(R.id.answer_edit_text)

                val answerRecordAudioButton = linearLayout.findViewById<Button>(R.id.answer_record_audio_button)
                var mediaRecorder: MediaRecorder? = null
                answerRecordAudioButton.setOnClickListener {
                    when {
                        answerRecordAudioButton.text == getString(R.string.delete_recording) -> {
                            getAnswerAudioFile().delete()
                            answerRecordAudioButton.text = getString(R.string.start_recording)
                        }
                        mediaRecorder == null -> {
                            mediaRecorder = startAudioRecording()
                            answerRecordAudioButton.text = getString(R.string.stop_recording)
                        }
                        else -> {
                            mediaRecorder?.apply {
                                stop()
                                release()
                            }
                            mediaRecorder = null
                            answerRecordAudioButton.text = getString(R.string.delete_recording)
                        }
                    }
                }

                AlertDialog.Builder(this).apply {
                    setTitle(question)
                    setView(linearLayout)
                    setNegativeButton(android.R.string.cancel) { _, _ -> }
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        mediaRecorder?.apply {
                            stop()
                            release()
                        }

                        sendAnswer(socialAppName, question, answerEditText.text.toString())
                        startActivity(socialAppIntent)
                    }
                    create()
                    show()
                }
            }
        }

        finish()
    }

    private fun sendAnswer(appName: String, question: String, answerText: String) {
        var answerAudioUuid = "null"

        getAnswerAudioFile().let {
            if (it.exists()) {
                answerAudioUuid = UUID.randomUUID().toString()
                postToServer(it.readBytes(), "/audio?uuid=$answerAudioUuid")
                it.delete()
            }
        }

        postToServer(JSONObject("""{
            "user_id": "$userId",
            "app_name": "$appName",
            "question": "$question",
            "answer_text": "$answerText",
            "answer_audio_uuid": "$answerAudioUuid"
        }""").toString().toByteArray(), "/answer")
    }

    private fun getAnswerAudioFile(): File {
        return cacheDir.resolve("social_gateway_answer_audio.aac")
    }

    private fun startAudioRecording() : MediaRecorder {
        return MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setOutputFile(getAnswerAudioFile().absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        getPreferences(Context.MODE_PRIVATE).edit().apply {
            putString("userId", userId)
            apply()
        }
    }
}

val appWidgetIdToSocialApp = mutableMapOf<Int, SocialApp>()

class MyAppWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val socialApp = appWidgetIdToSocialApp[appWidgetId] ?: return

            val pendingIntent = Intent(context, QuestionBeforeLaunchActivity::class.java).let {
                it.putExtra("socialAppName", socialApp.name)
                it.putExtra("socialAppPackageName", socialApp.packageName)

                PendingIntent.getActivity(context, appWidgetId, it, 0)
            }

            RemoteViews(context.packageName, R.layout.widget).let {
                it.setImageViewResource(R.id.widget_button, socialApp.imageId)
                it.setOnClickPendingIntent(R.id.widget_button, pendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, it)
            }
        }
    }
}

class WidgetConfiguratorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.social_apps_grid)

        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val socialAppsGrid = findViewById<GridLayout>(R.id.social_apps_grid)
        socialApps.forEach { socialApp ->
            initSocialAppInGrid(socialAppsGrid, this, socialApp) {
                appWidgetIdToSocialApp[appWidgetId] = socialApp

                AppWidgetManager.getInstance(this).let {
                    MyAppWidgetProvider.updateAppWidget(this, it, appWidgetId)
                }

                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                })

                finish()
            }
        }
    }
}
