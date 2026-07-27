package dev.gravity

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window
import org.lwjgl.system.Platform

object WindowsForegroundActivator {
    fun activate(glfwWindow: Long): Boolean {
        if (Platform.get() != Platform.WINDOWS) return true

        val user32 = User32.INSTANCE
        val target = HWND(Pointer.createConstant(glfwGetWin32Window(glfwWindow)))
        if (sameHandle(user32.GetForegroundWindow(), target)) return true

        val targetThread = user32.GetWindowThreadProcessId(target, IntByReference())
        val foreground = user32.GetForegroundWindow()
        val foregroundThread = if (foreground == null) {
            0
        } else {
            user32.GetWindowThreadProcessId(foreground, IntByReference())
        }
        val attached = targetThread != 0 &&
            foregroundThread != 0 &&
            targetThread != foregroundThread &&
            user32.AttachThreadInput(
                DWORD(targetThread.toLong()),
                DWORD(foregroundThread.toLong()),
                true,
            )

        try {
            user32.BringWindowToTop(target)
            user32.SetForegroundWindow(target)
            user32.SetFocus(target)
        } finally {
            if (attached) {
                user32.AttachThreadInput(
                    DWORD(targetThread.toLong()),
                    DWORD(foregroundThread.toLong()),
                    false,
                )
            }
        }
        return sameHandle(user32.GetForegroundWindow(), target)
    }

    private fun sameHandle(first: HWND?, second: HWND): Boolean =
        first != null && Pointer.nativeValue(first.pointer) == Pointer.nativeValue(second.pointer)
}
