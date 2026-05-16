package dev.branchlens.editor

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * A small square badge that renders 1-3 characters of text. Used as the gutter icon.
 */
class BranchLensBadgeIcon(
    private val text: String,
    private val accent: Accent = Accent.DEFAULT,
) : Icon {

    enum class Accent { DEFAULT, BLOCK, INSERT, MISSING }

    private val size = 12

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, gIn: Graphics, x: Int, y: Int) {
        val g = gIn.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val bg = backgroundFor(accent)
            val fg = foregroundFor(accent)

            g.color = bg
            g.fillRoundRect(x, y, size - 1, size - 1, 4, 4)
            g.color = bg.darker()
            g.drawRoundRect(x, y, size - 1, size - 1, 4, 4)

            val display = text.take(2)
            val font = (c?.font ?: Font(Font.SANS_SERIF, Font.BOLD, 9)).deriveFont(Font.BOLD, 9f)
            g.font = font
            val fm = g.fontMetrics
            val tx = x + (size - fm.stringWidth(display)) / 2
            val ty = y + (size + fm.ascent - fm.descent) / 2 - 1
            g.color = fg
            g.drawString(display, tx, ty)
        } finally {
            g.dispose()
        }
    }

    private fun backgroundFor(a: Accent): Color = when (a) {
        Accent.DEFAULT -> JBColor(Color(0x35, 0x6E, 0xC4), Color(0x4A, 0x88, 0xD8))
        Accent.BLOCK -> JBColor(Color(0xB0, 0x7A, 0x18), Color(0xD2, 0x95, 0x25))
        Accent.INSERT -> JBColor(Color(0x2E, 0x8B, 0x57), Color(0x3F, 0xA7, 0x6A))
        Accent.MISSING -> JBColor(Color(0xA0, 0x32, 0x32), Color(0xC9, 0x44, 0x44))
    }

    private fun foregroundFor(@Suppress("UNUSED_PARAMETER") a: Accent): Color =
        JBColor(Color.WHITE, Color.WHITE)
}
