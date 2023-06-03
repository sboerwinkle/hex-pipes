package com.fireduckdev.hex_pipes

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.coroutineScope
import com.fireduckdev.hex_pipes.om.SavedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.*

private var score = -1
private var unlockTime = Calendar.getInstance()
private var bmps: BitmapLibrary? = null

private const val saveFilename = "savedGame.json"
private const val prefsKeyScore = "score"
private const val prefsKeyUnlock = "unlock_millis"

class MainActivity : FragmentActivity() {
    private var unlockJob: Job? = null
    private var gameplayScene: GameplayScene? = null

    private val parent = FrameLayout.LayoutParams.MATCH_PARENT
    private val layoutParams = FrameLayout.LayoutParams(parent, parent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            // If the Activity was destroyed by the user,
            //   don't attempt to reload any saved game.
            val saveFile = File(cacheDir, saveFilename)
            if (saveFile.exists()) saveFile.delete()
        }

        // Not necessary to re-query preferences if we're just
        // responding to a configuration change
        if (score == -1) {
            val prefs = getPreferences(MODE_PRIVATE)
            score = prefs.getInt(prefsKeyScore, 0)
            unlockTime.timeInMillis = prefs.getLong(prefsKeyUnlock, 0)
        }
        if (bmps == null) bmps =
            BitmapLibrary(resources)
    }

    override fun onResume() {
        super.onResume()
        if (!Calendar.getInstance().before(unlockTime)) makeGameplayScene()
        else makeLockedScene()
    }

    private fun makeGameplayScene() {
        if (gameplayScene == null) {
            unlockJob?.cancel()
            setContentView(R.layout.activity_main)
            gameplayScene = GameplayScene()
        }
    }

    private fun makeLockedScene() {
        unlockJob?.cancel()
        val hourOfDay = unlockTime.get(Calendar.HOUR_OF_DAY)
        val minute = unlockTime.get(Calendar.MINUTE)
        setContentView(R.layout.locked)
        findViewById<TextView>(R.id.locked_time).text =
            String.format("%02d:%02d", hourOfDay, minute)
        unlockJob = lifecycle.coroutineScope.launchWhenResumed {
            delay(unlockTime.timeInMillis - Calendar.getInstance().timeInMillis)
            makeGameplayScene()
        }
    }

    override fun onPause() {
        getPreferences(MODE_PRIVATE).edit().apply {
            putInt(prefsKeyScore, score)
            putLong(prefsKeyUnlock, unlockTime.timeInMillis)
            apply()
        }
        gameplayScene?.export()
        unlockJob?.cancel()
        super.onPause()
    }

    fun lockClicked(target: View) {
        gameplayScene?.let {
            LockTimeFragment(it).show(supportFragmentManager, "lock")
        }
    }

    fun starClicked(target: View) {
        val packageName = getPackageName()
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        try {
            startActivity(marketIntent);
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("http://play.google.com/store/apps/details?id=$packageName")
                )
            )
        }
    }

    inner class GameplayScene : TimePickerDialog.OnTimeSetListener {
        private var mainView: TestViewGroup
        private var outerView: FrameLayout = findViewById(R.id.main_outer)
        private var scoreTextView: TextView = findViewById(R.id.score)

        init {
            scoreTextView.text = score.toString()

            val saveFile = File(cacheDir, saveFilename)
            var savedState: SavedState? = null
            if (saveFile.exists()) {
                try {
                    savedState = SavedState(JSONObject(saveFile.readText()))
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to load saved game",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            val newViewGroup = if (savedState != null) {
                TestViewGroup(baseContext, bmps!!, savedState)
            } else {
                TestViewGroup(baseContext, bmps!!, 0)
            }
            addLevel(0, newViewGroup)
            mainView = newViewGroup
            newViewGroup.finishedCallback = this::callback
        }

        fun export() {
            File(cacheDir, saveFilename)
                .writeText(mainView.export().export().toString(2))
        }

        private fun point() {
            scoreTextView.text = "${++score}"
        }

        private fun addLevel(index: Int, newViewGroup: TestViewGroup) {
            newViewGroup.solvedCallback = this::point
            outerView.addView(newViewGroup, index, layoutParams)
        }

        private fun callback() {
            mainView.finishedCallback = null
            val newView = TestViewGroup(baseContext, bmps!!, mainView.getNextStyle())
            addLevel(1, newView)
            val w = outerView.width.toFloat()
            newView.translationX = w
            val mainViewCapture = mainView
            ValueAnimator.ofFloat(0f, -w).apply {
                duration = 500
                addUpdateListener {
                    val f = it.animatedValue as Float
                    mainViewCapture.translationX = f
                    newView.translationX = f + w
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        outerView.removeViewAt(0)
                        mainView = newView
                        newView.finishedCallback = this@GameplayScene::callback
                    }
                })
                start()
            }
        }

        override fun onTimeSet(view: TimePicker?, hourOfDay: Int, minute: Int) {
            val now = Calendar.getInstance()
            unlockTime = now.clone() as Calendar
            unlockTime.set(Calendar.HOUR_OF_DAY, hourOfDay)
            unlockTime.set(Calendar.MINUTE, minute)
            unlockTime.set(Calendar.SECOND, 0)
            unlockTime.set(Calendar.MILLISECOND, 0)
            if (unlockTime.before(now)) unlockTime.add(Calendar.DAY_OF_MONTH, 1)
            export()
            gameplayScene = null
            mainView.solvedCallback = null
            mainView.finishedCallback = null
            makeLockedScene()
        }
    }

/*
    fun actionSubmit(target: View) {
        val rawText : String = findViewById<TextView>(R.id.textView).text.toString()
        val bang : String = getString(R.string.bang)
        val ix : Int = rawText.indexOf(bang).let {
            if (it == -1) {
                rawText.length
            } else {
                it
            }
        }
        val text = rawText.substring(0, ix)
        val bangs = (rawText.length - ix).div(bang.length);
        startActivity(Intent(this, ResultsActivity::class.java).apply {
            putExtra(resultsTextExtra, text)
            putExtra(resultsBangsExtra, bangs)
        })
    }
*/
}