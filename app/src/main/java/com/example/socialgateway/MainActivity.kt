package com.example.socialgateway

import android.app.AlertDialog
import android.os.AsyncTask
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
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
    return URL("http://192.168.178.23:5000$route").openConnection() as HttpURLConnection
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val userId = UUID.randomUUID().toString()  // TODO: store user_id


        val socialApps = listOf(
            SocialApp(resources.getString(R.string.whats_app), "com.whatsapp", R.id.whats_app_button),
            SocialApp(resources.getString(R.string.telegram), "org.telegram.messenger", R.id.telegram_button))
        socialApps.forEach { socialApp ->
            val button = findViewById<Button>(socialApp.buttonId)
            button.setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage(socialApp.packageName)
                if (intent == null) {
                    val message = resources.getString(R.string.X_was_not_found_on_your_device, socialApp.name)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
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
                        startActivity(intent)
                        Log.d("aaaaaa", "could not request question: ${exception.message.orEmpty()}")
                        return@execute
                    } finally {
                        questionConnection.disconnect()
                    }

                    runOnUiThread {
                        val answerEditText = EditText(this)
                        AlertDialog.Builder(this).apply {
                            setTitle(question)
                            setView(answerEditText)
                            setNegativeButton(android.R.string.cancel) { _, _ -> }
                            setPositiveButton(android.R.string.ok) { _, _ ->
                                AsyncTask.execute {
                                    val answerConnection = openConnection("/answer")
                                    try {
                                        answerConnection.requestMethod = "POST"
                                        answerConnection.doOutput = true
                                        val answer = answerEditText.text.toString()
                                        val answerJson = JSONObject(
                                            """{
                                                    user_id: "$userId",
                                                    app_name: "${socialApp.name}",
                                                    question: "$question",
                                                    answer: "$answer"
                                                    }"""
                                        )
                                        answerConnection.outputStream.write(answerJson.toString().toByteArray())
                                        if (answerConnection.responseCode != HTTP_OK) {
                                            throw ConnectException("response code ${answerConnection.responseCode}")
                                        }

                                    } catch (exception: ConnectException) {
                                        Log.d("aaaaaa", "could not send answer: ${exception.message.orEmpty()}")
                                    } finally {
                                        answerConnection.disconnect()
                                    }
                                }
                                startActivity(intent)
                            }
                            create()
                            show()
                        }
                    }
                }
            }
        }
    }
}
