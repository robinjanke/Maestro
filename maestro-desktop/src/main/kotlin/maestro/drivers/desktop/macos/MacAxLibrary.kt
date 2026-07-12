package maestro.drivers.desktop.macos

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings for macOS Accessibility (ApplicationServices.framework)
 * and CoreFoundation helpers with safe CF type handling.
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

    fun CFGetTypeID(cf: Pointer): Long

    fun CFStringGetTypeID(): Long

    fun CFBooleanGetTypeID(): Long

    fun CFBooleanGetValue(boolean: Pointer): Byte

    fun CFArrayGetTypeID(): Long

    companion object {
        val INSTANCE: CoreFoundationBridge = Native.load("CoreFoundation", CoreFoundationBridge::class.java)
    }
}

internal object MacAxValue {
    fun readString(pointer: Pointer?): String? {
        if (pointer == null) return null
        val cf = CoreFoundationBridge.INSTANCE
        if (cf.CFGetTypeID(pointer) != cf.CFStringGetTypeID()) {
            return null
        }
        val length = cf.CFStringGetLength(pointer)
        val maxSize = cf.CFStringGetMaximumSizeForEncoding(
            length,
            MacAxLibrary.K_CF_STRING_ENCODING_UTF8,
        ) + 1
        val buffer = Memory(maxSize.toLong())
        val ok = cf.CFStringGetCString(
            pointer,
            buffer,
            maxSize,
            MacAxLibrary.K_CF_STRING_ENCODING_UTF8,
        )
        return if (ok.toInt() != 0) buffer.getString(0) else null
    }

    fun readBool(pointer: Pointer?): Boolean? {
        if (pointer == null) return null
        val cf = CoreFoundationBridge.INSTANCE
        if (cf.CFGetTypeID(pointer) != cf.CFBooleanGetTypeID()) {
            return null
        }
        return cf.CFBooleanGetValue(pointer).toInt() != 0
    }

    fun readChildren(pointer: Pointer?): List<Pointer> {
        if (pointer == null) return emptyList()
        val cf = CoreFoundationBridge.INSTANCE
        if (cf.CFGetTypeID(pointer) != cf.CFArrayGetTypeID()) {
            return emptyList()
        }
        val count = cf.CFArrayGetCount(pointer)
        return (0 until count).mapNotNull { index ->
            cf.CFArrayGetValueAtIndex(pointer, index)
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
