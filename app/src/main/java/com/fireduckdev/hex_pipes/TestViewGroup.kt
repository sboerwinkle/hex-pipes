package com.fireduckdev.hex_pipes

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import com.fireduckdev.hex_pipes.om.SavedPipe
import com.fireduckdev.hex_pipes.om.SavedState
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val LQD_DRAIN = 0
private const val LQD_EMPTY = 1
private const val LQD_FILL = 2
private const val LQD_FULL = 3

private const val ACT_SPIN = 1
private const val ACT_STUN = 2
private const val ACT_FILL = 3
private const val ACT_EMPTY = 4
private const val ACT_ARM = 5
private const val ACT_POP = 6
private const val ACT_FALL_DONE = 7

// TODO Only two states still in use, change to bool
private const val ANIM_ACTIVE = 0
private const val ANIM_DEAD = 2

// Higher style numbers have higher precedence;
// this is based not just on rarity but also the interplay
// of the conditions that activate each of them
private const val STYLE_INK = 3
private const val STYLE_BLOOD = 2
private const val STYLE_GREY = 1

private val choreographer = Choreographer.getInstance()
private val rand = Random.Default

private const val spin_duration = 150L
private const val raise_duration = 150L
private const val lower_duration = 500L // Lower slowly for dramatic effect

// Should drain slightly faster than it fills,
// propagating and amplifying bubbles
private const val fill_duration = 500L
private const val drain_duration = 400L
private const val stun_duration = raise_duration

// Should be an odd number for an appropriately-formed hexagon
private const val board_size = 7
// cos(30)
private val packing = sqrt(3f) / 2f

private val hex_m = arrayOf(0, -1, -1, 0, 1, 1)
private val hex_n = arrayOf(1, 1, 0, -1, -1, 0)

private fun abortMillis(current_millis: Long, end_millis: Long, d1: Long, d2: Long): Long {
    return current_millis + d2 +
            if (current_millis >= end_millis) 0
            else if (end_millis - current_millis > d1) -d2
            else (current_millis - end_millis) * d2 / d1
}

class TestViewGroup : ConstraintLayout, Choreographer.FrameCallback {
    var coupler_dx = 0f
    val pipes = Array(board_size) {Array<PipeElement?>(board_size) {null} }
    val heap = MinHeap(board_size * board_size)

    // TODO dispose of all these lateinit vars by transitioning to builder class
    private var bgColor = 0
    var victoryTime = 0L
    var unfilled = -1
    private var scale: Float = 0f
    private var spacing: Float = 0f
    private lateinit var ovalBounds: RectF
    private lateinit var rectBounds: RectF
    private lateinit var longRectBounds: RectF
    private lateinit var bmps: BitmapLibrary
    private var m_x: Float = 0f
    private var m_y: Float = 0f
    private var origin_x: Float = 0f
    private var origin_y: Float = 0f

    var animState: Int = ANIM_ACTIVE
    var solvedCallback: (() -> Unit)? = null
    var finishedCallback: (() -> Unit)? = null

    constructor(c: Context, bmps: BitmapLibrary, savedState: SavedState) : super(c) {
        loadBmps(bmps);
        post {
            calcSpacing();
            loadBoard(savedState)
            animState = ANIM_ACTIVE
        }
    }

    constructor(c: Context, bmps: BitmapLibrary, style: Int) : super(c) {
        loadBmps(bmps);
        post {
            calcSpacing()
            generateBoard(style)
            animState = ANIM_ACTIVE
        }
    }

    private fun loadBmps(bmps: BitmapLibrary) {
        this.bmps = bmps
        coupler_dx = (bmps.pipe_arm.width - bmps.pipe_coupler.width) / 2f
        val wedgeRadius = bmps.pipe_wedge.height * 0.95f
        ovalBounds = RectF(-wedgeRadius, -wedgeRadius, wedgeRadius, wedgeRadius)
        val arm_radius = bmps.pipe_arm.width * 0.475f
        rectBounds = RectF(-arm_radius, 0f, arm_radius, bmps.pipe_arm.height.toFloat())
        longRectBounds = RectF(-arm_radius, 0f, arm_radius, bmps.pipe_arm_long.height.toFloat())
    }

    private fun calcSpacing() {
        // Our biggest task here is determining the scale.
        // Vertically, this is pretty straightforward.
        // Horizontally, the hexagon will be slightly narrower,
        // since alternating columns can "nestle" together.
        // Said another way, none of the 3 major lines through the hexagon run strictly horizontaly.
        val diagonalWidth = width / packing
        val smallestDimension = min(diagonalWidth, height.toFloat())
        // Leave an extra node's worth of space for padding
        spacing = smallestDimension / (board_size + 1)
        scale = spacing / (bmps.pipe_arm_long.height * 2f)
        m_x = spacing * packing
        m_y = spacing / 2f
        val radius = (board_size - 1) / 2
        origin_x = width / 2f - m_x * radius
        origin_y = height / 2f - (m_y + spacing) * radius
    }

    private fun generateBoard(style: Int) {
        var fluidFunc: () -> Int
        when (style) {
            STYLE_GREY -> {
                // Normal (mid-grey) background,
                // fluids are various greys.
                // Easiest style to activate, possibly by accident even.
                bgColor = Color.rgb(0x80, 0x80, 0x80)
                fluidFunc = {
                    var v = rand.nextInt(0x0, 0x60)
                    if (rand.nextBoolean()) v = 0xFF - v
                    Color.rgb(v, v, v)
                }
            }
            STYLE_INK -> {
                // Roughly pen-and-ink;
                // white background, black fluid.
                // Similar to GREY, but feels different since pipe walls blend in with filled pipes,
                // and there is only one color fluid.
                bgColor = Color.WHITE
                fluidFunc = { Color.BLACK }
            }
            STYLE_BLOOD -> {
                // Most dramatic style, most difficult to activate.
                // Black background makes unfilled pipes hard to see (and HUD invisible),
                // all fluids are reds.
                bgColor = Color.BLACK
                fluidFunc = { Color.rgb(rand.nextInt(0x30, 0x100), 0, 0) }
            }
            else -> {
                // Normal style is a light-grey background,
                // with slightly-dimmed rainbow colors
                // that wind up looking like Kool-Aid.
                bgColor = Color.rgb(0xC0, 0xC0, 0xC0)
                fluidFunc = { Color.HSVToColor(floatArrayOf(360 * rand.nextFloat(), 1f, 0.8f)) }
            }
        }
        unfilled = 0
        val time = SystemClock.uptimeMillis()
        val radius = (board_size - 1) / 2
        // If no previous state to load, make up a new level
        for (m in 0 until board_size) {
            for (n in max(0, radius - m) until min(board_size, radius * 3 - m + 1)) {
                pipes[m][n] = PipeElement(m, n)
                unfilled++
            }
        }
        for (col in pipes) for (pair in col.withIndex()) pair.value?.apply {
            if (0 == genArms()) {
                // Remove any dumb-looking nubs
                col[pair.index] = null
                unfilled--
            }
        }
        for (col in pipes) for (pipe in col) pipe?.apply {
            heap.add(this)
            addSpin(time, rand.nextInt(6))
        }
        colorize(time, fluidFunc)
    }

    private fun loadBoard (savedState: SavedState) {
        bgColor = savedState.background
        unfilled = 0
        val time = SystemClock.uptimeMillis()
        for (p in savedState.pipes) {
            val newPipe = PipeElement(p.m, p.n)
            pipes[p.m][p.n] = newPipe
            heap.add(newPipe)
            unfilled++
            if (p.color != null) {
                newPipe.setSrc(p.color, time)
            }
            newPipe.setArms(p.couplers)
        }
        if (savedState.finished) {
            victoryTime = 1L
            doFireworks(0)
        }
    }

    // Flood fill to find which areas need to be sources.
    // We use "dominantColor" to track flood-fill progress, but doesn't have to be that way
    private fun colorize(time: Long, fluidFunc: () -> Int) {
        var best: Int
        val candidates = MutableList<PipeElement>(0) {PipeElement(0, 0)}
        val border = ArrayDeque<PipeElement>(board_size * board_size)
        for (col in pipes) for (pipe in col) {
            if (pipe?.dominantColor != 0) continue
            pipe.dominantColor = 1
            best = 7
            candidates.clear()
            border.add(pipe)
            while (!border.isEmpty()) {
                val item = border.removeLast()
                val arms = item.arms.count { it != null }
                if (arms <= best) {
                    if (arms < best) {
                        best = arms
                        candidates.clear()
                    }
                    candidates.add(item)
                }
                for (i in 0 until 6) {
                    if (item.arms[i] == null) continue
                    item.getNeighbor(i)?.apply {
                        if (dominantColor == 0) {
                            dominantColor = 1
                            border.add(this)
                        }
                    }
                }
            }
            candidates[rand.nextInt(candidates.size)].setSrc(fluidFunc.invoke(), time)
        }
    }

    init {
        // Change default "don't draw" behavior for view groups
        setWillNotDraw(false)
    }

    fun export(): SavedState {
        return SavedState(
            victoryTime != 0L,
            bgColor,
            pipes.flatMap { row ->
                row.filterNotNull().map(PipeElement::export)
            }
        )
    }

    fun getNextStyle(): Int {
        var ret = 0
        for (m in 0 until board_size) {
            for (n in 0 until board_size) {
                pipes[m][n]?.let {
                    if (it.liquid_state != LQD_FULL) {
                        if (ret < STYLE_INK) ret = STYLE_INK
                    } else {
                        for (dir in 0 until 6) {
                            it.arms[dir]?.let { arm ->
                                if (arm.down) {
                                    val neigh = it.getNeighbor((dir + it.target_spin) % 6)!!
                                    if (neigh.dominantColor != it.dominantColor && ret < STYLE_BLOOD) ret = STYLE_BLOOD
                                } else {
                                    if (ret < STYLE_GREY) ret = STYLE_GREY
                                }
                            }
                        }
                    }
                }
            }
        }
        return ret
    }

    // TODO: Whatever this performClick thing is
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.apply {
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                if (victoryTime != 0L) {
                    if (SystemClock.uptimeMillis() > victoryTime + 1000L) {
                        finishedCallback?.invoke()
                    }
                } else {
                    // experimentally, 0s here are fine, but maybe clean this up
                    // later if it somehow runs before the initial post() (shouldn't)
                    val mRaw = ((x - origin_x) / m_x)
                    var m = mRaw.roundToInt()
                    val nRaw = ((y - origin_y - m * m_y) / spacing)
                    var n = nRaw.roundToInt()

                    // The above calculates the rough cell using a brick-like grid.
                    // we have to do some trickery to appropriately handle corners,
                    // and use a proper hex grid.
/*
     ++++++++++
     +B.    .C+
     +.      .+
     .        .
    .+   00   +.
     .        .
     +.      .+
     +D.    .A+
     ++++++++++
 */
                    val mRemainder = (mRaw - m) * (3f/2)
                    val nRemainder = nRaw - n
                    val diag1 = mRemainder + nRemainder
                    if (diag1 > 1) m++ // Diagram point A
                    else if (diag1 < -1) m-- // Diagram point B
                    else {
                        val diag2 = mRemainder - nRemainder
                        if (diag2 > 1) { m++; n-- } // Diagram point C
                        else if (diag2 < -1) { m--; n++ } // Diagram point D
                    }

                    if (m in 0 until board_size && n in 0 until board_size) {
                        pipes[m][n]?.let {
                            it.addSpin(SystemClock.uptimeMillis(), 1)
                            animState = ANIM_ACTIVE
                            invalidate()
                            performClick()
                        }
                    }
                }
            }
        }
        // Any event we receive we say we handled. Probably good enough for now
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        for (col in pipes) for (pipe in col) pipe?.draw(canvas)
        // Because of the falling animation at the end, couplers must be drawn
        //   after all pipes.
        for (col in pipes) for (pipe in col) pipe?.drawCouplers(canvas)
        super.onDraw(canvas)
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (animState == ANIM_DEAD) {
            return
        }

        val millis = frameTimeNanos / 1000000L

        while (true) {
            val e = heap.peek()
            if (e == null) break
            if (e.interruptTime > millis) break
            e.animate(e.interruptTime)
        }
        val framesRemaining = pipes.map { row ->
            row.map {
                it?.run {
                    animate(millis)
                    prepareForDraw(millis)
                } ?: 0
            }.maxBy{it} ?: 0
        }.maxBy{it} ?: 0

//            if (framesRemaining > 0) invalidate()
//            if (framesRemaining <= 1) state = ANIM_DYING
        invalidate()
        if (framesRemaining == 0) {
            if (unfilled == 0 && victoryTime == 0L) {
                victoryTime = millis
                doFireworks(millis)
                solvedCallback?.invoke()
                // In this case, don't die just yet, so we render once more.
            } else {
                animState = ANIM_DEAD
            }
        }
    }

    private fun doFireworks(millis: Long) {
        for ((x, row) in pipes.withIndex()) {
            for ((y, pipe) in row.withIndex()) {
                if (pipe == null) continue
                for ((ix, arm) in pipe.arms.withIndex()) {
                    if (arm == null || !arm.down) continue
                    val rot = (ix + pipe.target_spin) % 6
                    val delay = 2 * (x + y) + hex_m[rot] + hex_n[rot] - board_size + 1
                    val time = millis + 350 * delay
                    pipe.add(Action(time, ACT_POP, false, ix))
                    // It's probably done after a bit, just stop animating then
                    pipe.add(Action(time + 2000, ACT_FALL_DONE, false, ix))
                }
            }
        }
    }


    inner class PipeElement(val m: Int, val n: Int) {
        // TODO standardize variable names
        private val x: Float = origin_x + m * m_x
        private val y: Float = origin_y + m * m_y + n * spacing
        private var target_state: Int = 0
        private var current_state: Int = 0
        var spin: Int = 0
        var target_spin: Int = 0
        var angle = 0f
        private var finalSpinAction: Action? = null
        private var spinAction: Action? = null

        var interruptTime = Long.MAX_VALUE
        var stunAction: Action? = null
        var head = Action(0, 0)
        var tail = Action(Long.MAX_VALUE, 0, true)
        var heapIdx = -1

        var liquidAction: Action? = null
        var liquid_state = LQD_EMPTY
        var src = false
        var dominantColor: Int = 0
        var dominance: Int = 0
        var outputColor: Int = 0
        var outputTime = 0L
        val paint: Paint = Paint().apply { color = bgColor }
        // TODO these should be in the parent class
        private var fillColor: Int = 0
        private var deltRed = 0
        private var deltGreen = 0
        private var deltBlue = 0

        private val arm_matrix = Matrix()
        private val step_matrix = Matrix()
        private val drawing_matrix = Matrix()
        private val coupler_matrix = Matrix()

        val arms = Array<Coupler?>(6) { null }

        init {
            head.next = tail
            tail.prev = head
        }

        fun export(): SavedPipe {
            return SavedPipe(
                if (src) dominantColor else null,
                m,
                n,
                Array<Boolean>(6) {
                    arms[(it + 6 - target_spin) % 6] != null
                }
            )
        }

        fun add(x: Action) {
            var runner = head
            do runner = runner.next while (x.millis > runner.millis)
            x.prev = runner.prev
            x.next = runner
            x.prev.next = x
            x.next.prev = x

            if (x.sync && x.millis < interruptTime) {
                interruptTime = x.millis
                heap.bubbleUp(heapIdx)
            }
        }

        fun drop(x: Action) {
            x.prev.next = x.next
            x.next.prev = x.prev

            if (x.millis == interruptTime) {
                // Fine to re-calculate here, it's probably sync anyway
                var runner = head
                do runner = runner.next while (!runner.sync)
                interruptTime = runner.millis
                heap.bubbleDown(heapIdx)
            }
        }

        fun genArms(): Int {
            var ret = 0
            for (dir in 0 until 6) {
                getNeighbor(dir)?.let {
                    if (
                        if (dir in 1..3) {
                            it.arms[(dir+3)%6] != null
                        } else {
                            // 3/8ths chance for connection
                            rand.nextBits(3) < 3
                        }
                    ) {
                        arms[dir] = Coupler(dir)
                        ret++
                    }
                }
            }
            return ret
        }

        fun setArms(input: Array<Boolean>) {
            for ((ix, value) in input.withIndex()) {
                if (!value) continue
                val coupler = Coupler(ix)
                arms[ix] = coupler
                val other = getNeighborArm(ix)
                if (other != null) other.down = true
                else coupler.down = false
            }
        }

        fun setSrc(color: Int, time: Long) {
            dominantColor = color
            dominance = 1
            src = true
            fill(time)
        }

        fun setFillColor(c: Int) {
            fillColor = c
            deltRed = (c shr 16 and 0xFF) - (bgColor shr 16 and 0xFF)
            deltGreen = (c shr 8 and 0xFF) - (bgColor shr 8 and 0xFF)
            deltBlue = (c and 0xFF) - (bgColor and 0xFF)
        }

        fun getNeighbor(dir: Int): PipeElement? {
            val m2 = m + hex_m[dir]
            val n2 = n + hex_n[dir]
            return if (m2 in 0 until board_size && n2 in 0 until board_size) {
                pipes[m2][n2]
            } else {
                null
            }
        }

        fun getNeighborArm(dir: Int): Coupler? {
            return getNeighbor(dir)?.let {
                if (it.target_state == it.current_state) {
                    it.getOppositeArm(dir)
                } else {
                    null
                }
            }
        }

        fun getOppositeArm(dir: Int): Coupler? {
            return arms[(dir + 9 - target_spin) % 6]
        }

        init {
            step_matrix.postRotate(60f, x, y)
            arm_matrix.postTranslate(-bmps.pipe_arm.width / 2f, 0f)
            arm_matrix.postScale(scale, scale)
            arm_matrix.postTranslate(x, y)
        }

        fun fill(current_millis: Long) {
            if (stunAction != null || current_millis == liquidAction?.millis) return
            if (liquid_state == LQD_FILL || liquid_state == LQD_FULL) return
            setFillColor(dominantColor)
            val draining = liquid_state == LQD_DRAIN
            liquid_state = LQD_FILL
            val liquid_complete_millis = if (draining) {
                drop(liquidAction!!)
                abortMillis(
                    current_millis,
                    liquidAction!!.millis,
                    drain_duration,
                    fill_duration
                )
            } else {
                current_millis + fill_duration
            }
            Action(liquid_complete_millis, ACT_FILL, true).let {
                add(it)
                liquidAction = it
            }
            // Last thing we do so it's safely re-entrant
            if (draining) {
                outputOff(current_millis)
            }
        }

        private fun drain(current_millis: Long) {
            if (current_millis == liquidAction?.millis) return
            if (liquid_state == LQD_DRAIN || liquid_state == LQD_EMPTY) {
                return
            }
            val capture = liquidAction
            val liquid_millis = if (capture == null) {
                current_millis + drain_duration
            } else {
                drop(capture)
                abortMillis(
                    current_millis,
                    capture.millis,
                    fill_duration,
                    drain_duration
                )
            }
            liquid_state = LQD_DRAIN
            // TODO This could probably be rolled in with the case where capture == null
            if (outputColor != 0) outputLow()
            Action(liquid_millis, ACT_EMPTY, true).let {
                add(it)
                liquidAction = it
            }
        }

        fun animate(current_millis: Long) {
            while (current_millis >= head.next.millis) {
                val tmp = head.next
                handle(tmp)
                drop(tmp)
            }
        }

        fun interpColor(times_l: Long, div_l: Long): Int {
            val times = times_l.toInt()
            val div = div_l.toInt()
            return bgColor +
                    (deltRed * times / div shl 16) +
                    (deltGreen * times / div shl 8) +
                    (deltBlue * times / div)
        }

        fun prepareForDraw(current_millis: Long): Int {

            val sp = spinAction
            angle = if (sp != null) {
                60 * max(0f, 1 - (sp.millis - current_millis) / spin_duration.toFloat())
            } else {
                0f
            }

            for (arm in arms) arm?.prepareForDraw(current_millis)

            paint.setColor(
                when (liquid_state) {
                    LQD_FULL -> fillColor
                    LQD_FILL -> interpColor(
                        current_millis + fill_duration - liquidAction!!.millis,
                        fill_duration
                    )
                    LQD_DRAIN -> interpColor(
                        liquidAction!!.millis - current_millis,
                        drain_duration
                    )
                    else -> bgColor // else = empty
                }
            )

            return if (head.next == tail) 0 else 1
        }

        fun addSpin(current_millis: Long, amt: Int) {
            if (amt <= 0) return

            if (finalSpinAction == null) {
                // Update each arm, and any neighbors the arm points to
                for (i in 0 until 6) {
                    val mine = arms[(i + 6 - target_spin) % 6]
                    val his = getNeighborArm(i)
                    if (mine != null && his != null) {
                        mine.raise(current_millis)
                        his.raise(current_millis)
                    }
                }
            }

            val capture = finalSpinAction
            val completeMillis = if (capture != null) {
                capture.millis
            } else {
                couplerRaisedTime(current_millis)
            }
            val newFinal = Action(completeMillis + amt * spin_duration, ACT_SPIN, true)
            finalSpinAction = newFinal
            add(newFinal)
            if (capture != null) {
                drop(capture)
                if (capture == spinAction) {
                    Action(capture.millis, ACT_SPIN).let {
                        add(it)
                        spinAction = it
                    }
                }
            } else {
                spinAction = if (amt > 1) {
                    Action(completeMillis + spin_duration, ACT_SPIN).also {
                        add(it)
                    }
                } else {
                    newFinal
                }
            }
            target_state += amt
            target_spin = (spin + target_state - current_state) % 6
        }

        private fun couplerRaisedTime(current_millis: Long): Long {
            return arms.map {
                it?.let {
                    if (it.down) it.nextAction!!.millis
                    else current_millis
                } ?: current_millis
            }.maxBy { it }!!
        }

        private fun handle(a: Action) {
            val time = a.millis
            when (a.type) {
                ACT_SPIN -> {
                    current_state++
                    spin = (spin + 1) % 6
                    // TODO This should be true iff it was synchronous, maybe test that here

                    if (current_state == target_state) {
                        for (ix in 0 until 6) {
                            val mine = arms[ix]
                            val his = getNeighborArm((ix + spin) % 6)
                            if (mine != null && his != null) {
                                mine.lower(time)
                                his.lower(time)
                            }
                        }
                        finalSpinAction = null
                        spinAction = null
                    } else {
                        spinAction = if (current_state + 1 < target_state) {
                            Action(time + spin_duration, ACT_SPIN).also {
                                add(it)
                            }
                        } else {
                            finalSpinAction
                        }
                    }
                }
                ACT_ARM -> arms[a.arm]!!.act(time)
                ACT_POP -> arms[a.arm]!!.pop(time)
                ACT_FALL_DONE -> arms[a.arm]!!.hide()
                ACT_EMPTY -> {
                    liquid_state = LQD_EMPTY
                    outputOff(a.millis)
                    liquidAction = null
                    checkFill(a.millis)
                }
                ACT_FILL -> {
                    liquid_state = LQD_FULL
                    outputOn(a.millis)
                    // Leave liquidAction set until outputOn completes,
                    // to avoid re-entrant fill/drain issues
                    liquidAction = null
                    checkFill(a.millis)
                }
                ACT_STUN -> {
                    stunAction = null
                    checkFill(a.millis)
                }
            }
        }

        private fun checkFill(time: Long) {
            if (
                stunAction == null
                && dominance != 0
                && (dominantColor == fillColor || liquid_state == LQD_EMPTY)
            ) {
                fill(time)
            } else {
                drain(time)
            }
        }

        fun outputOn(current_millis: Long) {
            outputColor = fillColor
            unfilled--
            outputTime = current_millis
            for (dir in 0 until 6) {
                val neighbor = getNeighbor(dir)
                neighbor?.getOppositeArm(dir)?.let {
                    if (
                        it.down && it.nextAction == null
                        && (neighbor.outputColor == 0 || neighbor.outputTime == outputTime)
                    ) {
                        it.flow = true
                        neighbor.addColor(current_millis, outputColor)
                    }
                }
            }
        }

        fun outputOff(current_millis: Long) {
            for (i in 0 until 6) {
                val dir = (i + spin) % 6
                val neighbor = getNeighbor(dir)
                val neighborArm = neighbor?.getOppositeArm(dir)
                if (neighborArm?.flow == true) {
                    neighborArm.flow = false
                    neighbor.stun(current_millis)
                }
            }
        }

        fun outputLow() {
            val color = outputColor
            outputColor = 0
            unfilled++
            outputTime = 0
            for (i in 0 until 6) {
                val dir = (i + spin) % 6
                val neighbor = getNeighbor(dir)
                val neighborArm = neighbor?.getOppositeArm(dir)
                if (neighborArm?.flow == true) {
                    neighbor.rmColor(color)
                }
            }
        }

        fun addColor(current_millis: Long, color: Int) {
            if (dominantColor == color || dominantColor == 0)
            {
                dominantColor = color
                dominance++
                if (dominantColor == fillColor || liquid_state == LQD_EMPTY) {
                    fill(current_millis)
                }
            } else {
                dominance--
                if (dominance <= 0) recheckColors()
                if (dominance == 0) {
                    drain(current_millis)
                } else if (dominantColor == fillColor || liquid_state == LQD_EMPTY) {
                    fill(current_millis)
                }
            }
        }

        fun rmColor(color: Int) {
            if (dominantColor == color) {
                dominance--
                if (dominance <= 0) recheckColors()
            } else if (dominance == 0) {
                recheckColors()
            }
        }

        fun stun(current_millis: Long) {
            stunAction?.let { drop(it) }
            Action(current_millis + stun_duration, ACT_STUN, true).let {
                stunAction = it
                add(it)
            }
            drain(current_millis)
        }

        fun recheckColors() {
            if (src) {
                dominance = 100
                return
            }
            val colors = Array<Int>(6) {0}
            val amts = Array<Int>(6) {0}
            for (ix in 0 until 6) {
                if (arms[ix]?.flow != true) continue
                val c = getNeighbor((ix + spin) % 6)!!.outputColor
                if (c == 0) continue
                for (j in 0 until 6) {
                    if (colors[j] == c || colors[j] == 0) {
                        colors[j] = c
                        amts[j]++
                        break
                    }
                }
            }
            dominance = 0
            dominantColor = 0
            var best = 0
            for (ix in 0 until 6) {
                if (amts[ix] > best) {
                    dominance = amts[ix] - best
                    best = amts[ix]
                    dominantColor = colors[ix]
                } else if (amts[ix] > best - dominance) {
                    dominance = best - amts[ix]
                }
            }
        }

        fun draw(canvas: Canvas) {
            // First, draw the background
            val saveCount = canvas.save()
            //canvas.concat(arm_matrix)
            canvas.translate(x, y)
            // TODO scaling is constant across this widget, could be
            //   extracted from all the stuff that draws
            //   (but that would take a while :P)
            canvas.scale(scale, scale)
            canvas.rotate(angle)
            canvas.drawOval(ovalBounds, paint)
            for (i in 6 - spin until 12 - spin) {
                arms[i % 6]?.let {
                    canvas.drawRect(
                        if (victoryTime != 0L && it.down) longRectBounds else rectBounds,
                        paint
                    )
                }
                canvas.rotate(60f)
            }
            canvas.restoreToCount(saveCount)
            // Next, draw our pretty sprites on top
            drawing_matrix.set(arm_matrix)
            drawing_matrix.postRotate(angle, x, y)
            for (i in 6-spin until 12-spin) {
                arms[i%6].let {
                    canvas.drawBitmap(
                        if (it != null) {
                            if (victoryTime != 0L && it.down) bmps.pipe_arm_long else bmps.pipe_arm
                        } else {
                            bmps.pipe_wedge
                        },
                        drawing_matrix,
                        paint
                    )
                }
                drawing_matrix.postConcat(step_matrix)
            }
        }

        fun drawCouplers(canvas: Canvas) {
            drawing_matrix.set(arm_matrix)
            drawing_matrix.postRotate(angle, x, y)

            for (i in 6-spin until 12-spin) {
                arms[i%6]?.let {
                        coupler_matrix.apply {
                            set(drawing_matrix)
                            preTranslate(coupler_dx, it.pos)
                            postTranslate(it.x, it.y)
                        }
                        canvas.drawBitmap(bmps.pipe_coupler, coupler_matrix, paint)
                }
                drawing_matrix.postConcat(step_matrix)
            }
        }

        inner class Coupler (private val ix: Int) {
            var down: Boolean = true
            var pos = 0f
            // If I'm receiving from the outside
            var flow = false
            var nextAction: Action? = null
            var x = 0f
            var y = 0f
            var popTime = Long.MAX_VALUE
            var vx = 0f
            var vy = 0f

            fun raise(current_millis: Long) {
                if (flow) {
                    // This could probably be moved into the COUP_DOWN case, but maybe not
                    flow = false
                    val color = getNeighbor((ix + spin) % 6)!!.outputColor
                    if (color != 0) rmColor(color)
                    stun(current_millis)
                }
                if (down) {
                    if (nextAction == null) {
                        Action(
                            current_millis + raise_duration,
                            ACT_ARM,
                            false,
                            ix
                        ).let {
                            nextAction = it
                            add(it)
                        }
                    }
                } else {
                    nextAction?.let {
                        drop(it)
                        down = true
                        Action(
                            abortMillis(current_millis, it.millis, lower_duration, raise_duration),
                            ACT_ARM,
                            false,
                            ix
                        ).let {new ->
                            nextAction = new
                            add(new)
                        }
                    }
                }
            }

            fun lower(current_millis: Long) {
                // If this is called by someone else, it means we're stationary / settling.
                // We could be a bit behind though, so might still be raising
                if (down != (nextAction != null)) {
                    throw Exception("Can't lower unless raising or up!")
                }
                nextAction?.let {
                    down = false
                    drop(it)
                }
                Action(current_millis + lower_duration, ACT_ARM, true, ix).let {
                    nextAction = it
                    add(it)
                }
            }

            fun act(time: Long) {
                nextAction = null
                down = !down
                if (down) {
                    val dir = (ix + spin) % 6
                    val partner = getNeighbor(dir)!!
                    if (partner.outputColor != 0) {
                        flow = true
                        addColor(time, partner.outputColor)
                    }
                }
            }

            fun pop(time: Long) {
                popTime = time
                vx = (rand.nextFloat() - 0.5f) * spacing * 0.002f
                vy = (rand.nextFloat() - 0.5f) * spacing * 0.002f
            }

            fun hide() {
                // No need to keep animating, don't want weird overflow
                popTime = Long.MAX_VALUE
                // We could hide it properly, but frames aren't going to be any slower
                // during the fireworks (so no reason to optimize that particular case),
                // and checking for a "hidden" flag would add more delay to the regular drawing code
                y = height * 2f
            }

            fun prepareForDraw(current_millis: Long) {
                if (popTime <= current_millis) {
                    // If it's popped, it was otherwise done animating,
                    // no need to worry about pipe position.
                    val delt = current_millis - popTime
                    x = vx * delt
                    // TODO precalculate gravity
                    y = (vy + (3 * spacing / 1e6f) * delt) * delt
                } else {
                    val a = nextAction
                    pos = bmps.pipe_arm.height + bmps.pipe_coupler.height *
                            if (down) {
                                if (a != null) (a.millis - current_millis) / raise_duration.toFloat() - 1
                                else 0f
                            } else {
                                if (a != null) (current_millis - a.millis) / lower_duration.toFloat()
                                else -1f
                            }
                }
            }
        }
    }
}

class Action(
    val millis: Long,
    val type: Int,
    val sync: Boolean = false,
    val arm: Int = 0
) {
    var prev: Action = this
    var next: Action = this
}