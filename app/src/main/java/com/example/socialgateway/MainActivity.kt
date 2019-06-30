package com.example.socialgateway

import android.app.AlertDialog
import android.content.Context
import android.media.MediaRecorder
import android.os.AsyncTask
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL
import java.util.*

data class SocialApp(
    val name: String,
    val packageName: String,
    val buttonId: Int)

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
            Log.d("aaaaaa", "could not send answer: ${exception.message.orEmpty()}")
        } finally {
            answerConnection.disconnect()
        }
    }
}

class MainActivity : AppCompatActivity() {

    private var userId = "null"
    private lateinit var answerAudioFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        answerAudioFile = cacheDir.resolve("social_gateway_answer_audio.aac")

        userId = getPreferences(Context.MODE_PRIVATE).getString("userId", "").orEmpty()
        if (userId == "") {
            userId = UUID.randomUUID().toString()
        }

        val socialApps = listOf(
            SocialApp(resources.getString(R.string.whats_app), "com.whatsapp", R.id.whats_app_button),
            SocialApp(resources.getString(R.string.telegram), "org.telegram.messenger", R.id.telegram_button))
        socialApps.forEach { socialApp ->
            val button = findViewById<ImageView>(socialApp.buttonId)
            button.setOnClickListener {
                onButtonClick(socialApp)
            }
        }
    }

    private fun onButtonClick(socialApp: SocialApp) {
        val intent = packageManager.getLaunchIntentForPackage(socialApp.packageName)
        if (intent == null) {
            val message = resources.getString(R.string.X_was_not_found_on_your_device, socialApp.name)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }

        AsyncTask.execute {
            val question: String
            val questionConnection = openConnection("/question?app_name=${socialApp.name}")
            try {
                if (questionConnection.responseCode != HTTP_OK) {
                    throw ConnectException("response code ${questionConnection.responseCode}")
                }

                question = questionConnection.inputStream.reader().readText()
            } catch (exception: ConnectException) {
                runOnUiThread {
                    Toast.makeText(this, "server unreachable, starting app...", Toast.LENGTH_SHORT).show()
                }
                startActivity(intent)
                Log.d("aaaaaa", "could not request question: ${exception.message.orEmpty()}")
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
                    val valMediaRecorder = mediaRecorder
                    when {
                        answerRecordAudioButton.text == getString(R.string.delete_recording) -> {
                            answerAudioFile.delete()
                            answerRecordAudioButton.text = getString(R.string.start_recording)
                        }
                        valMediaRecorder == null -> {
                            mediaRecorder = startAudioRecording()
                            answerRecordAudioButton.text = getString(R.string.stop_recording)
                        }
                        else -> {
                            valMediaRecorder.apply {
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
                        val valMediaRecorder = mediaRecorder
                        if (valMediaRecorder != null) {
                            valMediaRecorder.apply {
                                stop()
                                release()
                            }
                            answerAudioFile.delete()
                        }

                        sendAnswer(userId, socialApp.name, question, answerEditText.text.toString())
                        startActivity(intent)
                    }
                    create()
                    show()
                }
            }
        }
    }

    private fun sendAnswer(userId: String, appName: String, question: String, answerText: String) {
        var answerAudioUuid = "null"

        if (answerAudioFile.exists()) {
            answerAudioUuid = UUID.randomUUID().toString()
            postToServer(answerAudioFile.readBytes(), "/audio?uuid=$answerAudioUuid")
            answerAudioFile.delete()
        }

        postToServer(JSONObject("""{
            "user_id": "$userId",
            "app_name": "$appName",
            "question": "$question",
            "answer_text": "$answerText",
            "answer_audio_uuid": "$answerAudioUuid"
        }""").toString().toByteArray(), "/answer")
    }

    private fun startAudioRecording() : MediaRecorder {
        return MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setOutputFile(answerAudioFile.absolutePath)
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
