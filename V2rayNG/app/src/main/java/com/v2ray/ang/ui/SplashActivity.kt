package com.v2ray.ang.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private val binding by lazy { ActivitySplashBinding.inflate(layoutInflater) }

    private val bootLines = listOf(
        "$ cyberguard --init",
        "[OK] Módulo de protección cargado",
        "[OK] Red privada configurada",
        "[OK] Firewall activo",
        "[OK] Canal de comunicación listo",
        "",
        "> Sistema listo."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // Hide action bar
        supportActionBar?.hide()

        // Fade in logo
        ObjectAnimator.ofFloat(binding.ivLogo, "alpha", 0f, 1f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        lifecycleScope.launch {
            delay(300)

            val sb = StringBuilder()

            for (line in bootLines) {
                if (line.isEmpty()) {
                    sb.append("\n")
                    binding.tvBoot.text = sb.toString()
                    delay(100)
                    continue
                }

                // Type each character
                for (char in line) {
                    sb.append(char)
                    binding.tvBoot.text = sb.toString() + "█"
                    delay(if (char == ' ') 5L else 12L)
                }

                // Remove cursor, add newline
                sb.append("\n")
                binding.tvBoot.text = sb.toString()

                // Pause between lines
                delay(if (line.startsWith("$")) 200L else 80L)
            }

            // Show status
            binding.tvStatus.text = "> Acceso concedido"
            ObjectAnimator.ofFloat(binding.tvStatus, "alpha", 0f, 1f).apply {
                duration = 300
                start()
            }

            delay(400)

            // Launch main activity
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
