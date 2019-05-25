package com.example.socialgateway

import android.app.AlertDialog
import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

data class SocialApp(
    val name: String,
    val packageName: String,
    val buttonId: Int)

class MainActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val socialApps = listOf(
            SocialApp(resources.getString(R.string.whats_app),
                "com.whatsapp",
                R.id.whats_app_button),
            SocialApp(resources.getString(R.string.telegram),
                "org.telegram.messenger",
                R.id.telegram_button))
        socialApps.forEach { socialApp ->
            val button = findViewById<Button>(socialApp.buttonId)
            button.setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage(socialApp.packageName)
                if (intent == null) {
                    val message = resources.getString(R.string.X_was_not_found_on_your_device, socialApp.name)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                } else {
                    startActivityAfterQuestion(intent, socialApp.name)
                }
            }
        }
    }

    private fun startActivityAfterQuestion(intent: Intent, appName: String) {
        val answer = EditText(this)
        AlertDialog.Builder(this).apply {
            val question = getQuestion(appName)
            setTitle(question)
            setView(answer)
            setNegativeButton(android.R.string.cancel) { _, _ -> }
            setPositiveButton(android.R.string.ok) { _, _ ->
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Calendar.getInstance().time)
                Log.d("answer", "started $appName ($now)")
                Log.d("answer", "$question - ${answer.text}")
                startActivity(intent)
            }
            create()
            show()
        }
    }

    private fun getQuestion(appName: String): String {
        return "What do you expect of using $appName right now?"
    }
}
