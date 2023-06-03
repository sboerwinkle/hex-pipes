package com.fireduckdev.hex_pipes

import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import androidx.fragment.app.DialogFragment
import java.util.*

class LockTimeFragment(val callback: TimePickerDialog.OnTimeSetListener) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val c = Calendar.getInstance()
        c.add(Calendar.HOUR_OF_DAY, 1)

        return TimePickerDialog(
            activity,
            callback,
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(activity)
        )
    }
}