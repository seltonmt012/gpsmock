package com.mooncity.gpsmock

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mooncity.gpsmock.databinding.ActivityScheduleBinding
import com.mooncity.gpsmock.trip.Occurrence
import com.mooncity.gpsmock.trip.Schedule
import com.mooncity.gpsmock.trip.Trip
import com.mooncity.gpsmock.trip.TripAlarms
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Calendar view of the saved trip: which days it runs, and the next departures. */
class ScheduleActivity : AppCompatActivity() {

    private lateinit var b: ActivityScheduleBinding

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private val dayLine = DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.GERMANY)
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY)

    private var month: YearMonth = YearMonth.now()
    private var trip: Trip? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScheduleBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + 20, bottom = bars.bottom + 32)
            insets
        }

        b.prevMonth.setOnClickListener { month = month.minusMonths(1); render() }
        b.nextMonth.setOnClickListener { month = month.plusMonths(1); render() }
        b.btnEdit.setOnClickListener {
            startActivity(Intent(this, TripActivity::class.java))
            finish()
        }
        b.btnDelete.setOnClickListener { confirmDelete() }

        buildWeekdayHeader()
    }

    override fun onResume() {
        super.onResume()
        trip = Trip.load(this)
        render()
    }

    private fun buildWeekdayHeader() {
        b.weekdayHeader.removeAllViews()
        for (d in DayOfWeek.entries) {
            b.weekdayHeader.addView(TextView(this).apply {
                text = Schedule.short(d)
                gravity = Gravity.CENTER
                textSize = 12f
                alpha = 0.6f
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            })
        }
    }

    private fun render() {
        b.monthLabel.text = month.format(monthFmt)

        val t = trip
        if (t == null) {
            b.tripHeadline.text = getString(R.string.schedule_none)
            b.calendarGrid.removeAllViews()
            b.upcomingList.removeAllViews()
            b.btnDelete.visibility = View.GONE
            b.btnEdit.setText(R.string.schedule_create)
            return
        }

        b.btnDelete.visibility = View.VISIBLE
        b.btnEdit.setText(R.string.schedule_edit)
        b.tripHeadline.text = getString(
            R.string.schedule_headline,
            t.startName.ifBlank { getString(R.string.trip_start) },
            t.endName.ifBlank { getString(R.string.trip_end) },
            Schedule.label(t.days),
            t.outTime.format(hhmm),
            t.returnTime.format(hhmm)
        )

        drawMonth(t)
        drawUpcoming(t)
    }

    private fun drawMonth(t: Trip) {
        b.calendarGrid.removeAllViews()

        val today = LocalDate.now()
        val first = month.atDay(1)
        // Monday-first grid, so blank out the days before the 1st.
        val leading = (first.dayOfWeek.value - 1)
        val cell = (40 * resources.displayMetrics.density).toInt()

        fun addCell(view: View) {
            view.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = cell
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(2, 2, 2, 2)
            }
            b.calendarGrid.addView(view)
        }

        repeat(leading) { addCell(View(this)) }

        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val runs = Schedule.runsOn(t, date)
            addCell(TextView(this).apply {
                text = day.toString()
                gravity = Gravity.CENTER
                textSize = 13f
                when {
                    runs -> {
                        setBackgroundResource(R.drawable.bg_day_active)
                        setTextColor(Color.WHITE)
                    }

                    date == today -> setBackgroundResource(R.drawable.bg_day_today)
                    else -> alpha = 0.55f
                }
            })
        }
    }

    private fun drawUpcoming(t: Trip) {
        b.upcomingList.removeAllViews()
        val list = Schedule.upcoming(t, LocalDate.now(), 8)

        if (list.isEmpty()) {
            b.upcomingList.addView(line(getString(R.string.schedule_no_upcoming), 0.7f))
            return
        }
        for (o in list) b.upcomingList.addView(line(describe(o), 1f))
    }

    private fun describe(o: Occurrence): String = getString(
        R.string.schedule_entry,
        o.date.format(dayLine),
        o.outStart.format(hhmm),
        o.outEnd.format(hhmm),
        o.returnStart.format(hhmm),
        o.returnEnd.format(hhmm)
    )

    private fun line(text: String, alphaValue: Float) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = alphaValue
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_delete)
            .setMessage(R.string.schedule_delete_msg)
            .setPositiveButton(R.string.schedule_delete) { _, _ ->
                Trip.clear(this)
                TripAlarms.cancel(this)
                if (Prefs.mode(this) == Prefs.MODE_TRIP) {
                    MockLocationService.stop(this)
                    Prefs.setMode(this, Prefs.MODE_STATIC)
                }
                trip = null
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
