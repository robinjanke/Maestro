package maestro.drivers.desktop.macos

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings for macOS Accessibility (ApplicationServices.framework)
 * and CoreFoundation string helpers.
 */
internal interface MacAxLibrary : Library {
    fun AXUIElementCreateApplication(pid: Int): Pointer?

    fun AXUIElementCopyAttributeValue(
        element: Pointer,
        attribute: Pointer,
        value: PointerByReference,
    ): Int

    fun AXUIElementPerformAction(element: Pointer, action: Pointer): Int

    fun AXUIElementSetAttributeValue(
        element: Pointer,
        attribute: Pointer,
        value: Pointer,
    ): Int

    fun AXIsProcessTrusted(): Boolean

    companion object {
        val INSTANCE: MacAxLibrary = Native.load("ApplicationServices", MacAxLibrary::class.java)

        const val K_AX_ERROR_SUCCESS = 0
        const val K_CF_STRING_ENCODING_UTF8 = 0x08000100

        val AXChildrenAttribute: Pointer by lazy { cfString("AXChildren") }
        val AXRoleAttribute: Pointer by lazy { cfString("AXRole") }
        val AXTitleAttribute: Pointer by lazy { cfString("AXTitle") }
        val AXIdentifierAttribute: Pointer by lazy { cfString("AXIdentifier") }
        val AXDescriptionAttribute: Pointer by lazy { cfString("AXDescription") }
        val AXValueAttribute: Pointer by lazy { cfString("AXValue") }
        val AXPositionAttribute: Pointer by lazy { cfString("AXPosition") }
        val AXSizeAttribute: Pointer by lazy { cfString("AXSize") }
        val AXFocusedAttribute: Pointer by lazy { cfString("AXFocused") }
        val AXEnabledAttribute: Pointer by lazy { cfString("AXEnabled") }
        val AXSelectedAttribute: Pointer by lazy { cfString("AXSelected") }
        val AXPressAction: Pointer by lazy { cfString("AXPress") }
        val AXFocusedUIElementAttribute: Pointer by lazy { cfString("AXFocusedUIElement") }

        fun cfString(value: String): Pointer {
            return CoreFoundationBridge.INSTANCE.CFStringCreateWithCString(
                null,
                value,
                K_CF_STRING_ENCODING_UTF8,
            ) ?: error("Failed to create CFString for '$value'")
        }
    }
}

private interface CoreFoundationBridge : Library {
    fun CFStringCreateWithCString(allocator: Pointer?, cStr: String, encoding: Int): Pointer?

    fun CFStringGetLength(theString: Pointer): Int

    fun CFStringGetMaximumSizeForEncoding(length: Int, encoding: Int): Int

    fun CFStringGetCString(
        theString: Pointer,
        buffer: Pointer,
        bufferSize: Int,
        encoding: Int,
    ): Byte

    fun CFArrayGetCount(theArray: Pointer): Int

    fun CFArrayGetValueAtIndex(theArray: Pointer, index: Int): Pointer?

    companion object {
        val INSTANCE: CoreFoundationBridge = Native.load("CoreFoundation", CoreFoundationBridge::class.java)
    }
}

internal object MacAxValue {
    fun readString(pointer: Pointer?): String? {
        if (pointer == null) return null
        val length = CoreFoundationBridge.INSTANCE.CFStringGetLength(pointer)
        val maxSize = CoreFoundationBridge.INSTANCE.CFStringGetMaximumSizeForEncoding(
            length,
            MacAxLibrary.K_CF_STRING_ENCODING_UTF8,
        ) + 1
        val buffer = Memory(maxSize.toLong())
        val ok = CoreFoundationBridge.INSTANCE.CFStringGetCString(
            pointer,
            buffer,
            maxSize,
            MacAxLibrary.K_CF_STRING_ENCODING_UTF8,
        )
        return if (ok.toInt() != 0) buffer.getString(0) else null
    }

    fun readChildren(pointer: Pointer?): List<Pointer> {
        if (pointer == null) return emptyList()
        val count = CoreFoundationBridge.INSTANCE.CFArrayGetCount(pointer)
        return (0 until count).mapNotNull { index ->
            CoreFoundationBridge.INSTANCE.CFArrayGetValueAtIndex(pointer, index)
        }
    }
}

internal object MacAxElement {
    fun copyAttribute(element: Pointer, attribute: Pointer): Pointer? {
        val valueRef = PointerByReference()
        val error = MacAxLibrary.INSTANCE.AXUIElementCopyAttributeValue(element, attribute, valueRef)
        if (error != MacAxLibrary.K_AX_ERROR_SUCCESS) {
            return null
        }
        return valueRef.value
    }
}
