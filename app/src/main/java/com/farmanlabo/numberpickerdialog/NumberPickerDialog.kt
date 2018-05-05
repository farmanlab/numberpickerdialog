package com.farmanlabo.numberpickerdialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.support.annotation.LayoutRes
import android.support.v4.app.DialogFragment
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v7.app.AlertDialog
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import java.math.BigDecimal
import kotlin.math.max

typealias OnConfirmListener = ((Bundle?, Float) -> Unit)
typealias OnNeutralListener = ((Bundle?, Float, NumberPickerDialog) -> Unit)
typealias OnCancelListener = ((Bundle?) -> Unit)
typealias ShouldCheckContinue = Boolean

data class NumberPickerConfigurator(
    val maxValue: Int,
    val minValue: Int,
    val defaultValue: Int,
    val numberPicker: NumberPicker
)

class NumberPickerDialog : DialogFragment() {

    private var onConfirmClick: OnConfirmListener? = null
    private var onNeutralClick: OnNeutralListener? = null
    private var onCancelled: OnCancelListener? = null
    private var onDismissed: OnCancelListener? = null

    private var decimalPoint: Int = 0
    private lateinit var containerView: LinearLayout

    private val numberPickerDataList = mutableListOf<NumberPickerConfigurator>()

    private lateinit var onLayoutListener: () -> Unit

    private var childWidth: Int? = null

    override fun onDetach() {
        onConfirmClick = null
        onNeutralClick = null
        onCancelled = null
        onDismissed = null
        super.onDetach()
    }

    override fun onDismiss(dialog: DialogInterface?) {
        onDismissed?.invoke(getParam())
        super.onDismiss(dialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val listener = DialogInterface.OnClickListener { dialog, which ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> {
                    dismiss()
                    onConfirmClick?.invoke(getParam(), getCombinedValue())
                }
                DialogInterface.BUTTON_NEGATIVE -> {
                    onCancelled?.invoke(getParam())
                }
            }
        }
        val activity = activity ?: throw error("activity is null.")
        val builder = AlertDialog.Builder(activity)
        arguments?.let argument@{ arg ->

            builder.setTitle(arg.getString("title", ""))
            builder.createNumberPicker()
            builder.setPositiveButton(arg.getString("positive"), listener)
            arg.getString("neutral")?.let {
                builder.setNeutralButton(it, listener)
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                onNeutralClick?.invoke(getParam(), getCombinedValue(), this)
            }
        }

        dialog.show()

        return dialog
    }

    override fun onCancel(dialog: DialogInterface?) {
        onCancelled?.invoke(getParam())
    }

    fun reset() {
        numberPickerDataList.forEach { data ->
            data.numberPicker.minValue = 0
            data.numberPicker.value = data.defaultValue
        }

        numberPickerDataList.forEach { data ->
            data.adjust()
        }
    }

    private fun getParam(): Bundle? = arguments?.getBundle("params")

    private fun AlertDialog.Builder.createNumberPicker() {
        val inflater = LayoutInflater.from(activity)
        val rootView = inflater.inflate(R.layout.container_layout, null)
        containerView = rootView.findViewById(R.id.container)
        val childLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }

        val parentView = arguments?.getInt("layoutId")?.let { layoutId ->
            if (layoutId == 0) return@let rootView
            // AlertDialog.Builder.setViewはViewを渡さないとcreate以降でしかgetViewできないので、Viewを渡す
            inflater.inflate(layoutId, null).also {
                (it as? ViewGroup)?.apply {
                    addView(rootView)
                } ?: throw IllegalArgumentException("Given layout parent must be view group.")
            }
        } ?: rootView

        setView(parentView)

        onLayoutListener = {
            if (childWidth == null) {

                childWidth = (containerView.width - (containerView.findViewById<TextView>(-1)?.width ?: 0)) /
                    numberPickerDataList.size
                numberPickerDataList.map { it.numberPicker }.forEach {
                    it.layoutParams = LinearLayout.LayoutParams(childWidth!!, it.layoutParams.height)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    containerView.viewTreeObserver.removeOnGlobalLayoutListener(onLayoutListener)
                } else {
                    containerView.viewTreeObserver.removeGlobalOnLayoutListener(onLayoutListener)
                }
            }
        }

        // 最大値の桁数 + 小数点数
        arguments?.let { arg ->
            decimalPoint = arg.getInt("decimalPoint", 0)
            val maxValue = arg.getFloat("maxValue", 0F).toBigDecimal()
                .let { if (decimalPoint == 0) it.stripTrailingZeros() else it.setScale(decimalPoint) }.toPlainString()
            val minValue = arg.getFloat("minValue", 0F).toBigDecimal()
                .let { if (decimalPoint == 0) it.stripTrailingZeros() else it.setScale(decimalPoint) }
                .toPlainString().padStart(maxValue.length, '-')
            val defaultValue = arg.getFloat("defaultValue", arg.getFloat("minValue")).toBigDecimal()
                .let { if (decimalPoint == 0) it.stripTrailingZeros() else it.setScale(decimalPoint) }
                .toPlainString().padStart(maxValue.length, '-')


            maxValue.mapIndexed { i, char ->
                (if (char == '.') {
                    TextView(context).apply {
                        id = -1
                        text = ". "
                    }
                } else {
                    val config = NumberPickerConfigurator(
                        maxValue = char.toString().toIntOrNull() ?: 9,
                        minValue = minValue[i].toString().toIntOrNull() ?: 0,
                        defaultValue = defaultValue[i].toString().toIntOrNull() ?: 0,
                        numberPicker = NumberPicker(context)
                    )
                    config.numberPicker.apply {
                        id = i
                        this.maxValue = if (i != 0) 9 else char.toString().toIntOrNull() ?: 0
                        this.value = config.defaultValue
                        this.minValue = 0
//                        this.setMaximumWidth(120)
                        setOnValueChangedListener { picker, oldVal, newVal ->
                            val numberPickerConfig = numberPickerDataList.find { it.numberPicker == this }
                            if (oldVal == 9 && newVal == picker.minValue) {
                                picker.onMoveUpValue()
                            }

                            var shouldCheckContinue = true

                            if (newVal == this.maxValue) {
                                shouldCheckContinue = picker.checkShouldChangeMaxValue()
                            }

                            if (shouldCheckContinue && newVal == numberPickerConfig?.minValue) {
                                shouldCheckContinue = picker.checkShouldChangeMinValue()
                            }

                            if (shouldCheckContinue &&
                                (oldVal == numberPickerConfig?.minValue || oldVal == numberPickerConfig?.maxValue) &&
                                newVal != numberPickerConfig.minValue && newVal != numberPickerConfig.maxValue) {
                                picker.nextPickerConfig()?.numberPicker?.adjustValue()
                            }
                        }
                        numberPickerDataList += config
                    }
                }).also {
                    containerView.addView(it, childLayoutParams)
                }
            }
            containerView.viewTreeObserver.addOnGlobalLayoutListener(onLayoutListener)
            numberPickerDataList.forEach { data ->
                data.adjust()
            }
        }
    }

    private fun NumberPickerConfigurator.adjust() {
        if (numberPicker.value == maxValue) {
            numberPicker.checkShouldChangeMaxValue()
        } else if (numberPicker.value == minValue) {
            numberPicker.checkShouldChangeMinValue()
        }
    }

    private fun NumberPicker.prevPickerConfig(): NumberPickerConfigurator? {
        val index = numberPickerDataList.map { it.numberPicker }.indexOf(this)
        return numberPickerDataList.getOrNull(index - 1)
    }

    private fun NumberPicker.nextPickerConfig(): NumberPickerConfigurator? {
        val index = numberPickerDataList.map { it.numberPicker }.indexOf(this)
        return numberPickerDataList.getOrNull(index + 1)
    }

    private fun NumberPicker.onMoveUpValue() {
        // Last NumberPicker
        val prevPicker = prevPickerConfig() ?: return
        // Reset min value
        minValue = 0
        if (prevPicker.numberPicker.maxValue != prevPicker.numberPicker.value) {
            prevPicker.numberPicker.value++
        } else {
            val morePrevPickerConfig = prevPicker.numberPicker.prevPickerConfig()
            if (morePrevPickerConfig != null) {
                prevPicker.numberPicker.value = 0
                prevPicker.numberPicker.onMoveUpValue()
            } else {
                prevPicker.numberPicker.checkShouldChangeMaxValue()
            }
        }

        if (prevPicker.numberPicker.maxValue == prevPicker.numberPicker.value) {
            prevPicker.numberPicker.checkShouldChangeMaxValue()
        }
    }

    private fun NumberPicker.checkShouldChangeMaxValue(): ShouldCheckContinue {
        // Check if all prev value is max
        var prev = prevPickerConfig()
        var next: NumberPickerConfigurator? = nextPickerConfig() ?: return false

        while (prev != null) {
            if (prev.numberPicker.value == prev.maxValue) {
                prev = prev.numberPicker.prevPickerConfig()
            } else {
                return true
            }
        }

        while (next != null) {
            next.numberPicker.maxValue = next.maxValue
            next.numberPicker.minValue = 0
            if (next.numberPicker.value == next.maxValue) {
                next = next.numberPicker.nextPickerConfig()
            } else {
                next.numberPicker.nextPickerConfig()?.numberPicker?.adjustValue()
                return false
            }
        }
        return false
    }

    private fun NumberPicker.checkShouldChangeMinValue(): ShouldCheckContinue {
        // Check if all prev value is min
        var prev = prevPickerConfig()
        var next: NumberPickerConfigurator? = nextPickerConfig() ?: return false

        while (prev != null) {
            if (prev.numberPicker.value == prev.minValue) {
                prev = prev.numberPicker.prevPickerConfig()
            } else {
                return true
            }
        }

        while (next != null) {
            next.numberPicker.minValue = next.minValue
            next.numberPicker.maxValue = 9
            if (next.numberPicker.value == next.minValue) {
                next = next.numberPicker.nextPickerConfig()
            } else {
                next.numberPicker.nextPickerConfig()?.numberPicker?.adjustValue()
                return false
            }
        }
        return false
    }

    private fun NumberPicker.adjustValue() {
        var picker = numberPickerDataList.find { it.numberPicker == this }
        while (picker != null) {
            picker.numberPicker.maxValue = 9
            picker.numberPicker.minValue = 0
            picker = picker.numberPicker.nextPickerConfig()
        }
    }

    private fun getCombinedValue(): Float = numberPickerDataList.map { it.numberPicker.value.toString() }
        .reversed()
        .toMutableList().apply {
            add(decimalPoint, ".")
        }.foldRight("") { s, acc -> "$s$acc" }.toFloat()

    companion object {
        @JvmOverloads
        fun newInstance(
            activity: FragmentActivity,
            maxValue: BigDecimal,
            minValue: BigDecimal,
            default: BigDecimal? = null,
            title: String? = null,
            params: Bundle? = null,
            tag: String = "tag",
            positiveLabel: String,
            onConfirmClick: OnConfirmListener,
            negativeLabel: String? = null,
            onCancel: OnCancelListener? = null,
            neutralLabel: String? = null,
            onNeutralClick: OnNeutralListener? = null,
            @LayoutRes customLayoutId: Int? = null,
            onDismiss: OnCancelListener? = null
        ): Fragment {
            if (maxValue < minValue) {
                throw IllegalArgumentException("maxValue must be larger than minValue.")
            }

            if (default != null && (default > maxValue || default < minValue)) {
                throw IllegalArgumentException("default value must be between minValue and maxValue.")
            }

            val args = Bundle().apply {
                title?.also { putString("title", title) }
                putFloat("minValue", minValue.toFloat())
                putFloat("maxValue", maxValue.toFloat())
                default?.let { putFloat("defaultValue", default.toFloat()) }
                putInt("decimalPoint", max(minValue.stripTrailingZeros().scale(), maxValue.stripTrailingZeros().scale()))
                putString("positive", positiveLabel)
                negativeLabel?.let { putString("negative", negativeLabel) }
                putString("neutral", neutralLabel)
                customLayoutId?.also { putInt("layoutId", customLayoutId) }
                params?.also { putBundle("params", params) }
            }

            return NumberPickerDialog().apply {
                arguments = args
                this.onConfirmClick = onConfirmClick
                this.onNeutralClick = onNeutralClick
                onCancelled = onCancel
                onDismissed = onDismiss
                show(activity.supportFragmentManager, tag)
            }
        }
    }
}
