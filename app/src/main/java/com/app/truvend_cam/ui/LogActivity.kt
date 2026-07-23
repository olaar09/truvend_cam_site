package com.app.truvend_cam.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.app.truvend_cam.databinding.ActivityLogBinding
import com.app.truvend_cam.util.AppLog

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClearLogs.setOnClickListener {
            AppLog.clear()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.logText.text = AppLog.snapshot().joinToString("\n")
        binding.logScroll.post {
            binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
