package com.example.socialgateway

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.os.AsyncTask
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL
import java.net.URLEncoder
import java.util.*

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


class SocialAppAdapter(private val context: Context, private val onClick: (Context, SocialApp) -> Unit)
        : BaseAdapter() {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val socialApp = socialApps[position]
        val imageView = convertView as? ImageView ?:ImageView(context).apply {
            adjustViewBounds = true
        }

        return imageView.apply {
            setImageResource(socialApp.imageId)
            setOnClickListener { onClick(context, socialApp) }
        }
    }

    override fun getItem(position: Int): Any {
        return socialApps[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getCount(): Int {
        return socialApps.size
    }
}


class MainActivity : AppCompatActivity() {

    private lateinit var userId: String
    private lateinit var preferences: SharedPreferences

    private fun openConnection(route: String): HttpURLConnection {
        return URL("http://192.168.178.23:5000$route").openConnection() as HttpURLConnection
    }

    private fun postToServer(data: ByteArray, route: String) {
        AsyncTask.execute {
            openConnection(route).apply {
                try {
                    requestMethod = "POST"
                    doOutput = true
                    outputStream.write(data)
                    if (responseCode != HTTP_OK) {
                        throw ConnectException("response code $responseCode")
                    }
                } catch (exception: ConnectException) {
                    log("could not send answer: ${exception.message.orEmpty()}")
                } finally {
                    disconnect()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.social_apps_grid)

        preferences = getPreferences(Context.MODE_PRIVATE)

        userId = preferences.getString("userId", "").ifEmpty {
            log("generating new userId")
            UUID.randomUUID().toString()
        }
        log("userId: $userId")

        val mainActivity = this

        findViewById<GridView>(R.id.social_apps_grid).adapter = SocialAppAdapter(mainActivity) { context, socialApp ->
            startActivity(Intent(context, MainActivity::class.java).apply {
                putExtra("socialAppName", socialApp.name)
                putExtra("socialAppPackageName", socialApp.packageName)
            })
        }

        // these are only set when started via widget
        val socialAppName = intent?.extras?.getString("socialAppName").orEmpty()
        val socialAppPackageName = intent?.extras?.getString("socialAppPackageName").orEmpty()

        // nothing else to do if started directly (not via widget)
        socialAppName.ifEmpty { return }
        socialAppPackageName.ifEmpty { return }

        val socialAppIntent = packageManager.getLaunchIntentForPackage(socialAppPackageName)
        if (socialAppIntent == null) {
            resources.getString(R.string.X_was_not_found_on_your_device, socialAppName).let {
                Toast.makeText(mainActivity, it, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }

        if (System.currentTimeMillis() - preferences.getLong(socialAppName, 0) < 86400000) {
            // last question for this app was asked less than 24 hours ago
            startActivity(socialAppIntent)
            log("already answered question today")
            finish()
            return
        }

        Toast.makeText(mainActivity, "requesting question from server...", Toast.LENGTH_SHORT).show()
        AsyncTask.execute {
            val question: String
            val encodedAppName = URLEncoder.encode(socialAppName, "utf-8")
            val questionConnection = openConnection("/question?app_name=$encodedAppName")
            try {
                if (questionConnection.responseCode != HTTP_OK) {
                    throw ConnectException("response code ${questionConnection.responseCode}")
                }

                question = questionConnection.inputStream.reader().readText()
            } catch (exception: ConnectException) {
                runOnUiThread {
                    Toast.makeText(mainActivity, "server unreachable, starting app...", Toast.LENGTH_SHORT).show()
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

                AlertDialog.Builder(mainActivity).apply {
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

                        // received question and sent answer -> no more questions for this app in the next 24 hours
                        preferences.edit().apply {
                            putLong(socialAppName, System.currentTimeMillis())
                            apply()
                        }
                    }
                    create()
                    show()
                }
            }
        }
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
        preferences.edit().apply {
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

            val pendingIntent = Intent(context, MainActivity::class.java).let {
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

        findViewById<GridView>(R.id.social_apps_grid).adapter = SocialAppAdapter(this) { context, socialApp ->
            appWidgetIdToSocialApp[appWidgetId] = socialApp

            AppWidgetManager.getInstance(context).let {
                MyAppWidgetProvider.updateAppWidget(context, it, appWidgetId)
            }

            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            })

            finish()
        }
    }
}
