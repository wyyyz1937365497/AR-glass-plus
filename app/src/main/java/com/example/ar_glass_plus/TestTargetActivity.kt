package com.example.ar_glass_plus

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * SAFE test target for the content VirtualDisplay — pure View system (same
 * input path as real apps; Compose swallows injected touches, RecyclerView
 * inside AndroidView doesn't scroll). Large click targets, scrollable list,
 * counter. No system settings, nothing that can break the dev session.
 */
class TestTargetActivity : Activity() {

    private var clickCount = 0
    private var scrollCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "AR-glass-plus Test Target"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        val status = TextView(this).apply {
            text = statusText()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        fun addButton(label: String, heightDp: Int, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(heightDp),
                )
                setOnClickListener {
                    clickCount++
                    Log.i("TestTarget", "click $label count=$clickCount")
                    status.text = statusText()
                }
                onClick
            })
        }

        addButton("Button A — 大按钮", 70) {}
        addButton("Button B", 70) {}

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TestTargetActivity)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 60
                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int,
                ): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(
                    TextView(parent.context).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    },
                ) {}

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as TextView).text = "Scroll item $position"
                }
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) {
                        scrollCount++
                        Log.i(
                            "TestTarget",
                            "rv scroll dy=$dy first=${(rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()}",
                        )
                        status.text = statusText()
                    }
                }
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(list)

        setContentView(root)
        root.setOnTouchListener { _, event ->
            Log.i("TestTarget", "root touch action=${event.action} x=${event.x} y=${event.y}")
            false
        }
        list.setOnTouchListener { _, event ->
            Log.i("TestTarget", "list touch action=${event.action} x=${event.x} y=${event.y}")
            false
        }
    }

    private fun statusText() = "Clicks: $clickCount · Scrolls: $scrollCount"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
