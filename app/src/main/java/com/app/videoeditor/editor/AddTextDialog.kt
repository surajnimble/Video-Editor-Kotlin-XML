package com.app.videoeditor.editor

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.animation.BounceInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R
import com.app.videoeditor.core.OverlayManager
import com.app.videoeditor.widget.DraggableTextView
import com.app.videoeditor.widget.TextEffects
import com.app.videoeditor.widget.TextStyles

/**
 * "Add Text" dialog (dialog_add_text.xml) ki saari UI logic. EditorActivity se
 * alag class me rakha gaya hai taaki editor screen pehle jitni clean ho.
 */
class AddTextDialog(
    private val context: Context,
    private val overlayManager: OverlayManager,
    private val onDismiss: () -> Unit = {}
) {

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
        dialog.setContentView(R.layout.dialog_add_text)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        // Activity edge-to-edge (targetSdk 37) hai, isliye ADJUST_RESIZE se window
        // auto-shrink nahi hota. Theme_Black_NoTitleBar_Fullscreen status bar
        // ko chhupa deta tha (top inset 0 -> UI status bar ke upar jaata tha),
        // isliye ab NoTitleBar theme use karte hain. IME (keyboard) insets ko
        // manually root padding me laga kar pura UI keyboard ke upar rakhte
        // hain (neeche ke panels keyboard ke peeche nahi chhupte).
        WindowCompat.setDecorFitsSystemWindows(dialog.window!!, false)
        val dialogRoot = dialog.findViewById<View>(R.id.dialogRoot)
        ViewCompat.setOnApplyWindowInsetsListener(dialogRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // IME inset me nav bar waise bhi included hai (keyboard nav bar ke upar hota hai),
            // isliye sirf ime.bottom — systemBars.bottom add karne se UI-keyboard ke beech
            // nav bar jitna extra gap aa jaata tha.
            val bottom = if (ime.bottom > 0) ime.bottom else systemBars.bottom
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottom)
            insets
        }
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val etText = dialog.findViewById<EditText>(R.id.etText)
        val rvStyles = dialog.findViewById<RecyclerView>(R.id.rvOptionStyles)
        val rvModes = dialog.findViewById<RecyclerView>(R.id.rvBottomOptions)
        val colorPicker = dialog.findViewById<ImageView>(R.id.ivColorPicker)

        // Custom color (pick) button -- sirf color modes me dikhta hai
        fun setColorPickerMode(visible: Boolean) {
            colorPicker.visibility = if (visible) View.VISIBLE else View.GONE
        }

        etText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        etText.setSingleLine(false)

        // FIX: TextView hamesha layout drawing ko compoundPadding ke clip me
        // kaat-ta hai, isliye LineBackgroundSpan ka rect left/right edge par
        // clip ho jaata tha (rounded corners cut). Transparent shadow layer
        // se clip dono taraf horizontalPadding jitna expand ho jaata hai --
        // shadow dikh nahi kyunki color transparent hai, lekin clipRect
        // (TextView.java:9356) mShadowRadius != 0 par clipLeft/clipRight
        // ko expand kar deta hai. Ab pill background left/right dono taraf
        // text ke aas-paar equal padding ke saath draw ho sakta hai.
        val previewHorizontalPadding = dpToPx(16f)
        etText.setShadowLayer(previewHorizontalPadding, 0f, 0f, Color.TRANSPARENT)

        // Initial defaults sirf EK BAAR yahan apply hote hain (dialog khulte waqt).
        // Font badalne par yeh dobara overwrite NAHI honge (neeche showFontPanel dekho).
        var currentStyle = TextStyles.byId("classic")
        var currentTextColor = currentStyle.defaultTextColor
        var currentBackgroundColor = currentStyle.defaultBackgroundColor
        var currentEffect = TextEffects.byId("none")
        var alignCycle = 1 // 0 = left, 1 = center, 2 = right

        val lineBgSpan = RoundedLineBackgroundSpan(
            backgroundColor = currentBackgroundColor,
            cornerRadius = dpToPx(18f), horizontalPadding = dpToPx(16f), verticalPadding = dpToPx(6f),
            alignment = alignCycle
        )

        fun applyGravity() {
            val horizontal = when (alignCycle) { 0 -> Gravity.START; 2 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
            etText.gravity = horizontal or Gravity.CENTER_VERTICAL
            lineBgSpan.alignment = alignCycle
            etText.invalidate()
        }

        etText.setText("Hello")
        etText.typeface = currentStyle.typeface
        etText.setTextColor(currentTextColor)
        applyGravity()
        etText.text?.let { it.setSpan(lineBgSpan, 0, it.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE) }

        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.setSpan(lineBgSpan, 0, s.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
            }
        })

        rvStyles.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        lateinit var modeAdapter: ModeSelectorAdapter

        fun showFontPanel() {
            setColorPickerMode(false)
            rvStyles.adapter = TextStyleAdapter { style ->
                currentStyle = style
                etText.typeface = style.typeface
                // FIX: sirf typeface badal raha hai -- color/background/
                // alignment/effect jaisa tha waisa hi rahega. (Pehle yahan
                // currentTextColor/currentBackgroundColor ko style ke defaults
                // se overwrite kar diya jaata tha, jo galat tha.)
            }
        }

        fun showTextColorPanel() {
            setColorPickerMode(true)
            colorPicker.setOnClickListener {
                showCustomColorDialog { color ->
                    currentTextColor = color
                    etText.setTextColor(color)
                }
            }
            rvStyles.adapter = ColorSwatchAdapter(
                onColorPicked = { color ->
                    currentTextColor = color
                    etText.setTextColor(color)
                }
            )
        }

        fun showBackgroundPanel() {
            setColorPickerMode(true)
            colorPicker.setOnClickListener {
                showCustomColorDialog { color ->
                    currentBackgroundColor = color
                    lineBgSpan.backgroundColor = color
                    etText.invalidate()
                }
            }
            rvStyles.adapter = ColorSwatchAdapter(
                includeNoneOption = true, // FIX: "No background" option add kiya
                onColorPicked = { color ->
                    currentBackgroundColor = color
                    lineBgSpan.backgroundColor = color
                    etText.invalidate()
                }
            )
        }

        fun showEffectsPanel() {
            setColorPickerMode(false)
            rvStyles.adapter = TextEffectAdapter { effect ->
                currentEffect = effect
                playEffectPreview(etText, effect.id) // FIX: ab actual demo animation chalti hai
            }
        }

        modeAdapter = ModeSelectorAdapter { mode ->
            when (mode) {
                TextToolMode.FONT -> showFontPanel()
                TextToolMode.TEXT_COLOR -> showTextColorPanel()
                TextToolMode.EFFECTS -> showEffectsPanel()
                TextToolMode.BACKGROUND -> showBackgroundPanel()
                TextToolMode.ALIGN -> {
                    alignCycle = (alignCycle + 1) % 3
                    modeAdapter.setAlignment(alignCycle)
                    applyGravity()
                }
            }
        }
        rvModes.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvModes.adapter = modeAdapter
        showFontPanel()

        dialog.findViewById<View>(R.id.btnDone).setOnClickListener {
            val text = etText.text.toString().ifBlank { "Text" }
            val alignmentInt = when (alignCycle) {
                0 -> DraggableTextView.ALIGN_LEFT
                2 -> DraggableTextView.ALIGN_RIGHT
                else -> DraggableTextView.ALIGN_CENTER
            }
            overlayManager.addText(text, currentTextColor, currentBackgroundColor, currentStyle.id, currentEffect.id, alignmentInt)
            dialog.dismiss()
        }
        dialog.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            hideKeyboard(dialog)
            onDismiss()
        }
        dialog.show()

        etText.requestFocus()
        etText.setSelection(etText.text?.length ?: 0)

        dialog.window?.decorView?.post {
            etText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etText, InputMethodManager.SHOW_FORCED)
        }
    }

    // FIX: effect select karte hi live demo -- typewriter text ko letter-by-letter
    // retype karta hai, pop/jump EditText ko chhota bounce dete hain. Yeh sirf
    // DIALOG PREVIEW ka demo hai -- exported video me real time-synced reveal
    // animation abhi implement nahi hai (usko frame-accurate timing chahiye,
    // alag scope ka kaam hai).
    private fun playEffectPreview(etText: EditText, effectId: String) {
        etText.clearAnimation()
        when (effectId) {
            "typewriter" -> {
                val fullText = etText.text.toString()
                if (fullText.isEmpty()) return
                val handler = Handler(Looper.getMainLooper())
                var i = 0
                fun typeNext() {
                    if (i <= fullText.length) {
                        etText.setText(fullText.substring(0, i))
                        etText.setSelection(etText.text?.length ?: 0)
                        i++
                        handler.postDelayed({ typeNext() }, 40L)
                    }
                }
                typeNext()
            }
            "pop" -> {
                etText.scaleX = 0.5f; etText.scaleY = 0.5f
                etText.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator()).setDuration(350).start()
            }
            "jump" -> {
                etText.translationY = -dpToPx(60f)
                etText.animate().translationY(0f)
                    .setInterpolator(BounceInterpolator()).setDuration(500).start()
            }
            // "none" -> koi animation nahi
        }
    }

    // FIX: eyedropper -- simple hex-code color picker (custom color-wheel banana
    // scope se bahar tha, isliye yeh lightweight fallback hai; baad me chaho to
    // isse ek proper color-wheel View se replace kar sakte ho).
    private fun showCustomColorDialog(onPicked: (Int) -> Unit) {
        val input = EditText(context).apply { hint = "#RRGGBB"; setSingleLine(true) }
        AlertDialog.Builder(context)
            .setTitle("Custom Color")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val hex = input.text.toString().trim()
                try {
                    onPicked(Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid color", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dpToPx(v: Float): Float = v * context.resources.displayMetrics.density

    private fun hideKeyboard(dialog: Dialog) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(dialog.window?.decorView?.windowToken, 0)
    }
}