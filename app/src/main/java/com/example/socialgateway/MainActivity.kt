package com.example.socialgateway

import android.annotation.SuppressLint
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.os.*
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v7.app.AppCompatActivity
import android.text.format.DateFormat
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

enum class IntentCategory { AskQuestion, Reflection }

class MainActivity : AppCompatActivity() {

    private lateinit var userId: String
    private lateinit var preferences: SharedPreferences

    private val channelId = "SocialGatewayChannelId"
    private val key = "hef3TF^Vg90546bvgFVL>Zzxskfou;aswperwrsf,c/x"

    private fun openConnection(route: String, arguments: String = ""): HttpURLConnection {
        assert(!route.contains('?'))
        return URL("http://192.168.178.23:5000$route?key=$key&$arguments").openConnection() as HttpURLConnection
    }

    private fun postToServer(data: ByteArray, route: String, arguments: String = "") {
        AsyncTask.execute {
            openConnection(route, arguments).apply {
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

    private fun requestQuestion(socialAppName: String, socialAppIntent: Intent? = null): String? {
        assert(Looper.myLooper() != Looper.getMainLooper())  // make sure network request is not done on UI thread

        val encodedAppName = URLEncoder.encode(socialAppName, "utf-8")
        val language = if (Locale.getDefault().language == "de") "german" else "english"
        val questionConnection = openConnection("/question","app_name=$encodedAppName&language=$language")
        try {
            if (questionConnection.responseCode != HTTP_OK) {
                throw ConnectException("response code ${questionConnection.responseCode}")
            }

            // success, return question
            return questionConnection.inputStream.reader().readText()
        } catch (exception: ConnectException) {
            // something went wrong. display error, start the app
            runOnUiThread {
                Toast.makeText(this, resources.getString(R.string.server_unreachable), Toast.LENGTH_SHORT).show()
            }
            socialAppIntent?.let { startActivity(it) }
            log("could not request question: ${exception.message.orEmpty()}")
            finish()
            return null
        } finally {
            questionConnection.disconnect()
        }
    }

    private fun createNotificationChannel() {
        // copy pasted from https://developer.android.com/training/notify-user/build-notification

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun scheduleReflectionQuestion(socialAppName: String) {
        AsyncTask.execute {
            val question = requestQuestion(socialAppName) ?: return@execute

            runOnUiThread {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("intentCategory", IntentCategory.Reflection)
                    putExtra("question", question)
                    putExtra("socialAppName", socialAppName)
                }
                val pendingIntent = PendingIntent.getActivity(this, question.hashCode(), intent, 0)

                val builder = NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.placeholder)
                    .setContentTitle(getString(R.string.reflection_question))
                    .setContentText(question)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(question))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)

                // show the reflection question notification with some minutes delay
                Handler().postDelayed({
                    // notificationId is a unique int for each notification that you must define
                    val notificationId = System.currentTimeMillis() % Int.MAX_VALUE
                    NotificationManagerCompat.from(this).notify(notificationId.toInt(), builder.build())
                }, 10 * 60 * 1000)
            }
        }
    }

    private fun today(): String? {
        return DateFormat.format("dd.MM.yyyy", Date()) as String?
    }

    @SuppressLint("InflateParams")
    private fun showResponseDialog(question: String, socialAppName: String, socialAppIntent: Intent? = null) {
        assert(question.isNotBlank() && socialAppName.isNotBlank())

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

                // send the answer to the server and start the app
                sendAnswer(socialAppName, question, answerEditText.text.toString())

                // socialAppIntent is null for reflection questions
                if (socialAppIntent != null) {
                    scheduleReflectionQuestion(socialAppName)
                    startActivity(socialAppIntent)

                    // track when the question was answered, so more questions are asked for this app today
                    preferences.edit().apply {
                        putString(socialAppName, today())
                        putString("lastQuestionDate", today())
                        val questionsOnLastQuestionDate = preferences.getInt("questionsOnLastQuestionDate", 0)
                        putInt("questionsOnLastQuestionDate", questionsOnLastQuestionDate + 1)
                        apply()
                    }
                }
            }
            create()
            show()
        }
    }

    private fun questionResponseProcess(socialAppName: String, socialAppPackageName: String) {
        assert(socialAppName.isNotBlank() && socialAppPackageName.isNotBlank())

        val mainActivity = this

        val socialAppIntent = packageManager.getLaunchIntentForPackage(socialAppPackageName)

        // check if the given app is installed on the device
        if (socialAppIntent == null) {
            resources.getString(R.string.X_was_not_found_on_your_device, socialAppName).let {
                Toast.makeText(mainActivity, it, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }

        // check if the user was already asked a question for this app or two questions for any apps today
        if (preferences.getString(socialAppName, "") == today()
            || (preferences.getString("lastQuestionDate", "") == today()
                && preferences.getInt("questionsOnLastQuestionDate", 0) >= 2)) {
            startActivity(socialAppIntent)
            finish()
            return
        }

        // request a question from the server
        Toast.makeText(mainActivity, resources.getString(R.string.requesting_question_from_server), Toast.LENGTH_SHORT).show()
        AsyncTask.execute {
            val question = requestQuestion(socialAppName, socialAppIntent) ?: return@execute

            runOnUiThread {
                showResponseDialog(question, socialAppName, socialAppIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.social_apps_grid)

        createNotificationChannel()

        preferences = getPreferences(Context.MODE_PRIVATE)

        userId = preferences.getString("userId", "").orEmpty().ifBlank {
            log("generating new userId")
            UUID.randomUUID().toString()
        }
        log("userId: $userId")

        findViewById<GridView>(R.id.social_apps_grid).adapter = SocialAppAdapter(this) { context, socialApp ->
            startActivity(Intent(context, MainActivity::class.java).apply {
                putExtra("intentCategory", IntentCategory.AskQuestion)
                putExtra("socialAppName", socialApp.name)
                putExtra("socialAppPackageName", socialApp.packageName)
            })
        }

        intent?.extras?.let {
            when (it.getSerializable("intentCategory") as? IntentCategory) {
                IntentCategory.AskQuestion -> {
                    questionResponseProcess(it.getString("socialAppName").orEmpty(),
                                            it.getString("socialAppPackageName").orEmpty())
                }
                IntentCategory.Reflection -> {
                    showResponseDialog(it.getString("question").orEmpty(),
                                       it.getString("socialAppName").orEmpty())
                }
            }
        }
    }

    private fun sendAnswer(appName: String, question: String, answerText: String) {
        var answerAudioUuid = "null"

        getAnswerAudioFile().let {
            if (it.exists()) {
                answerAudioUuid = UUID.randomUUID().toString()
                postToServer(it.readBytes(), "/audio", "uuid=$answerAudioUuid")
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
                it.putExtra("intentCategory", IntentCategory.AskQuestion)
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
