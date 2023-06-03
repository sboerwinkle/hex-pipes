package com.fireduckdev.hex_pipes

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fireduckdev.hex_pipes.R

class BitmapLibrary(val resources: Resources) {
    val pipe_wedge: Bitmap = BitmapFactory.decodeResource(resources,
        R.drawable.pipe_wedge
    )
    val pipe_arm: Bitmap = BitmapFactory.decodeResource(resources,
        R.drawable.pipe_arm
    )
    val pipe_arm_long: Bitmap = BitmapFactory.decodeResource(resources,
        R.drawable.pipe_arm_long
    )
    val pipe_coupler: Bitmap = BitmapFactory.decodeResource(resources,
        R.drawable.pipe_coupling
    )
}